package com.wearosgpx.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.core.content.ContextCompat
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import androidx.core.app.ServiceCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseState
import androidx.health.services.client.data.ExerciseUpdate
import androidx.health.services.client.data.LocationAvailability
import androidx.health.services.client.data.LocationData
import androidx.health.services.client.data.SampleDataPoint
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.wearosgpx.R
import com.wearosgpx.data.gpx.GeoPoint
import com.wearosgpx.data.gpx.GeoUtils
import com.wearosgpx.data.local.LapEntity
import com.wearosgpx.data.local.TrackPointEntity
import com.wearosgpx.data.local.WearGpxDatabase
import com.wearosgpx.data.repository.RunRepository
import com.wearosgpx.health.ExerciseClientManager
import com.wearosgpx.health.ExerciseMessage
import com.wearosgpx.navigation.NavProgress
import com.wearosgpx.navigation.RouteNavigator
import com.wearosgpx.sync.RunSyncManager
import com.wearosgpx.sync.toPayload
import com.wearosgpx.presentation.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Foreground service that owns the workout. It hosts [ExerciseClientManager],
 * relays live metrics into [stateFlow] for the UI, and appends every GPS sample
 * to Room via [RunRepository].
 *
 * It is both *started* (so it survives the Activity being dismissed mid-run) and
 * *bound* (so the UI can read state and send start/pause/stop commands).
 */
class ExerciseService : LifecycleService() {

    private lateinit var exerciseManager: ExerciseClientManager
    private lateinit var repository: RunRepository

    private val _stateFlow = MutableStateFlow(ExerciseServiceState())
    val stateFlow = _stateFlow.asStateFlow()

    private val binder = LocalBinder()
    private var activeRunId: Long? = null

    // In-memory running totals so we never re-read the whole track per sample.
    private var lastPoint: GeoPoint? = null
    private var cumulativeMeters: Double = 0.0

    @Volatile
    private var startingRun = false

    // Route following / cue state.
    private var navigator: RouteNavigator? = null
    private var wasOffCourse = false
    private var lastOffCourseBuzzMs = 0L
    private var announcedTurnAtMeters = Double.NaN

    // Lap state.
    private var lapStartCumulativeMeters = 0.0
    private var lapStartEpochMillis = 0L
    private var lapNumber = 0
    private var lapHrSum = 0.0
    private var lapHrCount = 0
    private val _lapEvents = MutableSharedFlow<LapEntity>(extraBufferCapacity = 4)
    val lapEvents: SharedFlow<LapEntity> = _lapEvents.asSharedFlow()

    private val vibrator: Vibrator by lazy {
        (getSystemService(VibratorManager::class.java)).defaultVibrator
    }

    private val toneGenerator: ToneGenerator? by lazy {
        runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80) }.getOrNull()
    }

    /**
     * Wall-clock instant of device boot. WHS timestamps are durations since boot;
     * adding them to this yields a real epoch time for each sample.
     */
    private val bootInstant: Instant =
        Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())

    override fun onCreate() {
        super.onCreate()
        val healthClient = HealthServices.getClient(this)
        exerciseManager = ExerciseClientManager(healthClient)
        repository = RunRepository(WearGpxDatabase.getInstance(this).runDao())
        collectExerciseUpdates()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground()
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    // --- Public control surface (called from the UI through the binder) ---

    /** Warms up GPS/HR before the run (Garmin-style acquisition on the preview screen). */
    fun prepareRun() {
        lifecycleScope.launch { runCatching { exerciseManager.prepareExercise() } }
    }

    /** Cancels a warm-up if the user backs out without starting (frees GPS). */
    fun stopPrepare() {
        if (startingRun || activeRunId != null) return
        lifecycleScope.launch {
            runCatching { exerciseManager.endExercise() }
            _stateFlow.value = _stateFlow.value.copy(
                exerciseState = null,
                locationAvailability = null,
            )
        }
    }

    /** Creates a run row, then asks WHS to begin tracking. */
    fun startRun(routePoints: List<GeoPoint>? = null, title: String = "Run") {
        startingRun = true
        lifecycleScope.launch {
            try {
                val startMs = System.currentTimeMillis()
                val runId = repository.startRun(startMs, title)
                activeRunId = runId
                lastPoint = null
                cumulativeMeters = 0.0
                lapStartCumulativeMeters = 0.0
                lapStartEpochMillis = startMs
                lapNumber = 0
                lapHrSum = 0.0
                lapHrCount = 0
                navigator = routePoints
                    ?.let { RouteNavigator(it) }
                    ?.takeIf { it.isUsable }
                wasOffCourse = false
                announcedTurnAtMeters = Double.NaN
                _stateFlow.value = _stateFlow.value.copy(
                    isTracking = true,
                    runId = runId,
                    startEpochMillis = startMs,
                    error = null,
                )
                exerciseManager.startExercise()
                // Start cue: firm buzz + acknowledgement beep.
                vibrator.vibrate(VibrationEffect.createOneShot(220, VibrationEffect.DEFAULT_AMPLITUDE))
                runCatching { toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 200) }
            } catch (e: Exception) {
                Log.e(TAG, "startRun failed", e)
                _stateFlow.value = _stateFlow.value.copy(error = e.message)
            }
        }
    }

    fun pauseRun() = lifecycleScope.launch { runCatching { exerciseManager.pauseExercise() } }
    fun resumeRun() = lifecycleScope.launch { runCatching { exerciseManager.resumeExercise() } }

    /** Ends the WHS exercise; finalization happens when the ENDED state arrives. */
    fun endRun() = lifecycleScope.launch { runCatching { exerciseManager.endExercise() } }

    /** Manual lap marker (button). Records the distance since the last lap. */
    fun manualLap() {
        if (activeRunId == null) return
        val lapDistance = cumulativeMeters - lapStartCumulativeMeters
        if (lapDistance < 1.0) return
        recordLap(isManual = true, lapDistanceMeters = lapDistance, newStartCumulative = cumulativeMeters)
    }

    // --- Internal: drain the WHS flow into state + Room ---

    private fun collectExerciseUpdates() {
        lifecycleScope.launch {
            exerciseManager.exerciseUpdateFlow.collect { message ->
                when (message) {
                    is ExerciseMessage.ExerciseUpdateMessage -> onExerciseUpdate(message.update)
                    is ExerciseMessage.LocationAvailabilityMessage ->
                        onLocationAvailability(message.availability)
                }
            }
        }
    }

    /** Garmin-style "GPS ready" chime when a fix is first acquired. */
    private fun onLocationAvailability(availability: LocationAvailability) {
        val prev = _stateFlow.value.locationAvailability
        val nowAcquired = availability == LocationAvailability.ACQUIRED_TETHERED ||
            availability == LocationAvailability.ACQUIRED_UNTETHERED
        val wasAcquired = prev == LocationAvailability.ACQUIRED_TETHERED ||
            prev == LocationAvailability.ACQUIRED_UNTETHERED
        if (nowAcquired && !wasAcquired) {
            vibrator.vibrate(VibrationEffect.createOneShot(180, VibrationEffect.DEFAULT_AMPLITUDE))
            runCatching { toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 200) }
        }
        _stateFlow.value = _stateFlow.value.copy(locationAvailability = availability)
    }

    private suspend fun onExerciseUpdate(update: ExerciseUpdate) {
        val state = update.exerciseStateInfo.state
        val metrics = update.latestMetrics

        // Latest scalar metrics (take the freshest sample in the batch).
        val hr = metrics.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value
        val speed = metrics.getData(DataType.SPEED).lastOrNull()?.value
        val distance = metrics.getData(DataType.DISTANCE_TOTAL)?.total?.toDouble()
        val calories = metrics.getData(DataType.CALORIES_TOTAL)?.total?.toDouble()
        val checkpoint = update.activeDurationCheckpoint

        // Persist every GPS sample in this batch.
        val runId = activeRunId
        val locations = metrics.getData(DataType.LOCATION)
        val heading = locations.lastOrNull()?.value?.bearing?.toFloat()?.takeIf { it in 0f..360f }
        var lastGeo: GeoPoint? = null
        if (runId != null && locations.isNotEmpty()) {
            // Build the whole batch with cumulative distance computed incrementally
            // in memory, then write it in one transaction.
            val batch = ArrayList<TrackPointEntity>(locations.size)
            for (sample in locations) {
                val geo = sample.toGeoPoint(bootInstant)
                lastPoint?.let { cumulativeMeters += GeoUtils.haversineMeters(it, geo) }
                lastPoint = geo
                lastGeo = geo
                batch += TrackPointEntity(
                    runId = runId,
                    latitude = geo.latitude,
                    longitude = geo.longitude,
                    altitudeMeters = geo.elevationMeters,
                    epochMillis = geo.epochMillis ?: System.currentTimeMillis(),
                    heartRateBpm = hr,
                    speedMetersPerSec = speed,
                    cumulativeDistanceMeters = cumulativeMeters,
                )
            }
            repository.addTrackPoints(batch)
        }

        // Accumulate HR for the current lap's average.
        if (runId != null && hr != null) {
            lapHrSum += hr
            lapHrCount += 1
        }

        // Auto-lap each kilometre (lap-relative). A batch could cross >1 boundary.
        while (runId != null &&
            cumulativeMeters - lapStartCumulativeMeters >= AUTO_LAP_METERS
        ) {
            recordLap(
                isManual = false,
                lapDistanceMeters = AUTO_LAP_METERS,
                newStartCumulative = lapStartCumulativeMeters + AUTO_LAP_METERS,
            )
        }

        // Route following: snap the freshest position onto the planned route.
        val navProgress = lastGeo?.let { geo -> navigator?.update(geo, wasOffCourse) }
        navProgress?.let { handleNavCues(it) }

        _stateFlow.value = _stateFlow.value.copy(
            exerciseState = state,
            nav = navProgress ?: _stateFlow.value.nav,
            distanceMeters = distance ?: _stateFlow.value.distanceMeters,
            activeDurationMillis = checkpoint?.activeDuration?.toMillis()
                ?: _stateFlow.value.activeDurationMillis,
            durationCheckpointEpochMillis = checkpoint?.time?.toEpochMilli()
                ?: _stateFlow.value.durationCheckpointEpochMillis,
            heartRateBpm = hr ?: _stateFlow.value.heartRateBpm,
            speedMetersPerSec = speed ?: _stateFlow.value.speedMetersPerSec,
            totalCalories = calories ?: _stateFlow.value.totalCalories,
            latestLocation = lastGeo ?: _stateFlow.value.latestLocation,
            headingDegrees = heading ?: _stateFlow.value.headingDegrees,
        )

        if (state == ExerciseState.ENDED || state == ExerciseState.ENDING) {
            finalizeRun()
        }
    }

    /**
     * Records a completed lap: persists it, emits a [lapEvents] event (for the
     * banner + screen wake), and alerts with a distinct buzz + beep.
     */
    private fun recordLap(isManual: Boolean, lapDistanceMeters: Double, newStartCumulative: Double) {
        val runId = activeRunId ?: return
        val now = System.currentTimeMillis()
        lapNumber += 1
        val lap = LapEntity(
            runId = runId,
            lapNumber = lapNumber,
            startEpochMillis = lapStartEpochMillis,
            endEpochMillis = now,
            distanceMeters = lapDistanceMeters,
            durationMillis = now - lapStartEpochMillis,
            avgHeartRateBpm = if (lapHrCount > 0) lapHrSum / lapHrCount else null,
            isManual = isManual,
        )
        lifecycleScope.launch { runCatching { repository.addLap(lap) } }
        _lapEvents.tryEmit(lap)

        // Reset accumulators for the next lap.
        lapStartCumulativeMeters = newStartCumulative
        lapStartEpochMillis = now
        lapHrSum = 0.0
        lapHrCount = 0

        // Distinct triple buzz + a short beep.
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 120, 80, 120, 80, 120), -1))
        runCatching { toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 200) }
    }

    /** Buzz on off-course transitions (re-buzz every 20s while still off) and on approaching turns. */
    private fun handleNavCues(p: NavProgress) {
        val now = System.currentTimeMillis()
        if (p.isOffCourse) {
            if (!wasOffCourse || now - lastOffCourseBuzzMs > 20_000L) {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 120, 200), -1))
                lastOffCourseBuzzMs = now
            }
        }
        wasOffCourse = p.isOffCourse

        val turn = p.nextTurn
        if (turn != null && turn.distanceMeters <= 60.0) {
            val turnAbsMeters = p.distanceAlongMeters + turn.distanceMeters
            if (announcedTurnAtMeters.isNaN() ||
                kotlin.math.abs(turnAbsMeters - announcedTurnAtMeters) > 5.0
            ) {
                // One distinct "turn ahead" buzz (same for left/right — direction is on screen).
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 150, 90, 150), -1))
                announcedTurnAtMeters = turnAbsMeters
            }
        }
    }

    private suspend fun finalizeRun() {
        val runId = activeRunId ?: return
        repository.finishRun(runId, System.currentTimeMillis())
        activeRunId = null
        startingRun = false
        _stateFlow.value = _stateFlow.value.copy(isTracking = false)
        Log.i(TAG, "Run $runId finalized.")

        // Hand the finished run to the phone (queues + delivers when in range).
        runCatching {
            repository.getRunWithPoints(runId)?.let { rwp ->
                RunSyncManager(this).send(rwp.toPayload())
                repository.markSynced(runId)
                Log.i(TAG, "Run $runId queued for phone sync.")
            }
        }.onFailure { Log.e(TAG, "Run sync failed", it) }

        // Stop cue: long double-buzz + descending beep.
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 250, 120, 250), -1))
        runCatching { toneGenerator?.startTone(ToneGenerator.TONE_PROP_NACK, 250) }
        delay(700)  // let the cue finish before the service (and ToneGenerator) tears down

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // --- Foreground notification ---

    private fun startForeground() {
        createChannel()
        val pending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording run")
            .setContentText("WearOsGpx is tracking your workout")
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setContentIntent(pending)

        // Surface a run chip on the watch face (Wear Ongoing Activity) so Power-saver
        // mode — where the screen sleeps during a run — can tap straight back to the app.
        OngoingActivity.Builder(this, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_launcher)
            .setTouchIntent(pending)
            .setStatus(Status.Builder().addTemplate("Recording run").build())
            .build()
            .apply(this)

        val notification: Notification = builder.build()

        // The `health` FGS type requires BODY_SENSORS at runtime (API 34+) or
        // startForeground throws. Location is always required; add health only
        // when granted, so a run can still start with HR disabled.
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Workout tracking",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    inner class LocalBinder : Binder() {
        val service: ExerciseService get() = this@ExerciseService
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { toneGenerator?.release() }
    }

    companion object {
        private const val TAG = "ExerciseService"
        private const val CHANNEL_ID = "workout_tracking"
        private const val NOTIFICATION_ID = 1
        private const val AUTO_LAP_METERS = 1000.0

        fun startService(context: Context) {
            val intent = Intent(context, ExerciseService::class.java)
            context.startForegroundService(intent)
        }
    }
}

/** WHS location sample -> our domain [GeoPoint], with a real epoch timestamp. */
private fun SampleDataPoint<LocationData>.toGeoPoint(boot: Instant): GeoPoint {
    val loc = value
    val altitude = loc.altitude.takeIf { it.isFinite() }
    return GeoPoint(
        latitude = loc.latitude,
        longitude = loc.longitude,
        elevationMeters = altitude,
        epochMillis = boot.plus(timeDurationFromBoot).toEpochMilli(),
    )
}
