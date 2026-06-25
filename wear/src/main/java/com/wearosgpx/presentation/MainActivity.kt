package com.wearosgpx.presentation

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.sqrt
import androidx.core.content.ContextCompat
import androidx.health.services.client.data.ExerciseState
import androidx.health.services.client.data.LocationAvailability
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.ambient.AmbientLifecycleObserver
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.wearosgpx.R
import com.wearosgpx.data.gpx.BaseMap
import com.wearosgpx.data.gpx.BaseMapStore
import com.wearosgpx.data.gpx.GeoPoint
import com.wearosgpx.data.gpx.GeoUtils
import com.wearosgpx.data.gpx.GpxRoute
import com.wearosgpx.data.gpx.RouteCatalog
import com.wearosgpx.data.gpx.RouteUpdates
import com.wearosgpx.data.local.LapEntity
import com.wearosgpx.data.local.WearGpxDatabase
import com.wearosgpx.data.repository.RunRepository
import com.wearosgpx.navigation.NavProgress
import com.wearosgpx.settings.RunDisplayMode
import com.wearosgpx.settings.WatchSettings
import com.wearosgpx.navigation.TurnDirection
import com.wearosgpx.navigation.UpcomingTurn
import com.wearosgpx.presentation.map.BreadcrumbCanvas
import com.wearosgpx.presentation.map.MapMode
import com.wearosgpx.sync.RouteIndexPublisher
import com.wearosgpx.service.ExerciseService
import com.wearosgpx.service.ExerciseServiceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Host activity. Interaction model for the OnePlus Watch 2R (one usable button):
 *
 *  - Bottom hardware button  → short press: Start / Pause / Resume; long press: Stop.
 *  - Horizontal swipe        → move between run screens (map / controls).
 *  - Map follow vs overview  → automatic from exercise state (no tap toggle).
 *  - Swipe-to-dismiss         → blocked while a run is active (so you can't exit by accident).
 *
 * Touch equivalents for every button action exist on the control screen, so the
 * app is fully usable even if the hardware button doesn't deliver key events.
 */
class MainActivity : ComponentActivity() {

    private val boundService = mutableStateOf<ExerciseService?>(null)
    private val permissionsGranted = mutableStateOf(false)
    private var startAfterPermissions = false

    /** The route to navigate for the next run (chosen on the preview screen). */
    private var routePoints: List<GeoPoint>? = null

    // Always-on / ambient state. The observer MUST be attached in onCreate (not
    // dynamically) or the system never marks our activity always-on and hands the
    // screen to the watch face on timeout. We instead gate behaviour on [wantAmbient]:
    // during a run we stay in our dim ambient screen (W5 can sleep, ~1 Hz updates);
    // otherwise we step aside so the normal watch face shows.
    private val isAmbient = mutableStateOf(false)
    private val ambientTick = mutableStateOf(0)  // bumped ~once/min so ambient stats refresh
    private var wantAmbient = false

    // Self-managed dim: the 2R won't let a third-party app hold the *system* ambient
    // display (it reverts to the watch face), so during a run we keep the screen on
    // and dim it ourselves after [DIM_AFTER_MS] of no touch — rendering the dark
    // ambient screen + dropping brightness to near-zero (real OLED saving). A tap
    // brightens again. The GPS/HR sensing stays on the BES2700 regardless.
    private var dimJob: Job? = null
    private var lastTouchMs = 0L
    private var runMode = RunDisplayMode.ALWAYS_ON
    private var dimmed = false

    private val ambientObserver: AmbientLifecycleObserver by lazy {
        AmbientLifecycleObserver(
            this,
            object : AmbientLifecycleObserver.AmbientLifecycleCallback {
                override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
                    if (wantAmbient) {
                        isAmbient.value = true            // stay in our low-power run screen
                    } else {
                        moveTaskToBack(true)              // not running → let the watch face show
                    }
                }
                override fun onUpdateAmbient() {
                    ambientTick.value = ambientTick.value + 1
                }
                override fun onExitAmbient() {
                    isAmbient.value = false
                }
            },
        )
    }

    // Transient lap summary banner (auto-dismisses).
    private val lapBanner = mutableStateOf<LapEntity?>(null)
    private var lapJob: Job? = null
    private var lapDismissJob: Job? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val svc = (service as ExerciseService.LocalBinder).service
            boundService.value = svc
            lapJob?.cancel()
            lapJob = lifecycleScope.launch { svc.lapEvents.collect { onLapCompleted(it) } }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            boundService.value = null
            lapJob?.cancel()
        }
    }

    private fun onLapCompleted(lap: LapEntity) {
        lapBanner.value = lap
        // Wake the screen so the lap summary is visible even from ambient (best-effort).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        lapDismissJob?.cancel()
        lapDismissJob = lifecycleScope.launch {
            delay(4500)
            lapBanner.value = null
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Location is essential; body sensors / activity recognition are optional
        // (HR just won't be recorded if denied), so don't block on them.
        permissionsGranted.value = hasEssentialPermissions()
        if (permissionsGranted.value && startAfterPermissions) {
            startAfterPermissions = false
            doStart()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionsGranted.value = hasEssentialPermissions()
        // Arm always-on once, up front — attaching it lazily mid-run never marks the
        // activity always-on, so the watch face takes over on screen timeout.
        lifecycle.addObserver(ambientObserver)
        // Publish the route catalog so the phone can list/inspect/delete routes.
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { RouteIndexPublisher.publish(applicationContext) }
        }
        setContent {
            WearApp(
                serviceState = boundService,
                permissionsGranted = permissionsGranted,
                isAmbient = isAmbient,
                ambientTick = ambientTick,
                lapBanner = lapBanner,
                onAlwaysOnEnabled = { setAlwaysOn(it) },
                onLap = { boundService.value?.manualLap() },
                onPrepareRun = { boundService.value?.prepareRun() },
                onStopPrepare = { boundService.value?.stopPrepare() },
                onRequestPermissions = { permissionLauncher.launch(REQUIRED_PERMISSIONS) },
                onRouteChosen = { routePoints = it?.points },
                onStart = { requestOrStart() },
                onPause = { boundService.value?.pauseRun() },
                onResume = { boundService.value?.resumeRun() },
                onStop = { boundService.value?.endRun() },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(
            Intent(this, ExerciseService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    }

    override fun onStop() {
        super.onStop()
        runCatching { unbindService(connection) }
    }

    override fun onResume() {
        super.onResume()
        // Re-check on return from the system permission/settings screen so the UI
        // reflects grants made outside our in-app launcher.
        permissionsGranted.value = hasEssentialPermissions()
    }

    // --- Hardware button (bottom / stem) -------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Log every key so we can confirm what the Watch 2R's bottom button emits
        // (filter logcat for "WearOsGpxKeys"). STEM_PRIMARY never reaches here — the
        // system owns the home button.
        Log.d("WearOsGpxKeys", "onKeyDown keyCode=$keyCode")
        if (isStemKey(keyCode)) {
            if (event.repeatCount == 0) event.startTracking()  // enables onKeyLongPress
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (isStemKey(keyCode)) {
            boundService.value?.endRun()   // long press = Stop
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isStemKey(keyCode)) {
            // Fire short-press only if a long-press didn't already consume it.
            if (event.isTracking && !event.isCanceled) onPrimaryShortPress()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /** Start when idle, Pause when active, Resume when paused. */
    private fun onPrimaryShortPress() {
        val state = boundService.value?.stateFlow?.value?.exerciseState
        when {
            state == ExerciseState.ACTIVE -> boundService.value?.pauseRun()
            state != null && state.isPaused -> boundService.value?.resumeRun()
            else -> requestOrStart()
        }
    }

    // --- Run control helpers -------------------------------------------------

    private fun requestOrStart() {
        if (hasEssentialPermissions()) {
            doStart()
        } else {
            startAfterPermissions = true
            permissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    private fun doStart() {
        ExerciseService.startService(this)
        boundService.value?.startRun(routePoints)
    }

    /** GPS is the only hard requirement; everything else is best-effort. */
    private fun hasEssentialPermissions() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Toggled by the run state. During a run we hold the screen on (so it never
     * times out to the watch face — the OEM won't let us own the system ambient
     * display) and run our own dim timer. Cleared when the run ends so normal
     * screen-off + wrist-raise behaviour returns.
     */
    private fun setAlwaysOn(enabled: Boolean) {
        if (enabled) {
            runMode = WatchSettings.runDisplayMode(this)
            wantAmbient = true
            when (runMode) {
                RunDisplayMode.ALWAYS_ON -> {
                    // Hold the screen on; we dim it ourselves after idle.
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    noteInteraction()
                }
                RunDisplayMode.TRUE_AMBIENT -> {
                    // No keep-screen-on: let the system go ambient; the observer keeps
                    // our dim screen up (where the OEM allows it).
                }
                RunDisplayMode.POWER_SAVER -> {
                    // Let the screen (and W5) sleep; onEnterAmbient steps aside to the
                    // watch face, and the ongoing-activity chip taps back in.
                    wantAmbient = false
                }
            }
        } else {
            wantAmbient = false
            dimJob?.cancel()
            exitDim()
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** In Always-on mode, any touch wakes the screen to full brightness and re-arms the dim timer. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (wantAmbient && runMode == RunDisplayMode.ALWAYS_ON) noteInteraction()
        return super.dispatchTouchEvent(ev)
    }

    private fun noteInteraction() {
        lastTouchMs = SystemClock.elapsedRealtime()
        if (dimmed) exitDim()
        dimJob?.cancel()
        dimJob = lifecycleScope.launch {
            delay(DIM_AFTER_MS)
            if (wantAmbient && SystemClock.elapsedRealtime() - lastTouchMs >= DIM_AFTER_MS) enterDim()
        }
    }

    /**
     * Always-on dim: keep the full run screen (map, heading arrow, stats) exactly as
     * it was — just drop the panel brightness right down. No swap to a stripped
     * ambient layout, so the arrow/UI stay visible, only dimmer.
     */
    private fun enterDim() {
        dimmed = true
        window.attributes = window.attributes.apply { screenBrightness = 0.01f }
    }

    private fun exitDim() {
        dimmed = false
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    companion object {
        /** Idle time before the run screen self-dims. */
        private const val DIM_AFTER_MS = 15_000L

        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.POST_NOTIFICATIONS,
        )

        private fun isStemKey(keyCode: Int) = keyCode == KeyEvent.KEYCODE_STEM_PRIMARY ||
            keyCode == KeyEvent.KEYCODE_STEM_1 ||
            keyCode == KeyEvent.KEYCODE_STEM_2 ||
            keyCode == KeyEvent.KEYCODE_STEM_3
    }
}

/**
 * App navigation (no nav library — a small state machine):
 *   RouteList → (select) → Preview → (Start) → Activity → (Stop) → RouteList
 *
 * "Activity" is itself a 2-page pager: the live Map (shown first) with the
 * RunControls one swipe to the right. App-exit is blocked during a run.
 */
@Composable
fun WearApp(
    serviceState: State<ExerciseService?>,
    permissionsGranted: State<Boolean>,
    isAmbient: State<Boolean>,
    ambientTick: State<Int>,
    lapBanner: State<LapEntity?>,
    onAlwaysOnEnabled: (Boolean) -> Unit,
    onLap: () -> Unit,
    onPrepareRun: () -> Unit,
    onStopPrepare: () -> Unit,
    onRequestPermissions: () -> Unit,
    onRouteChosen: (GpxRoute?) -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    val service by serviceState
    val exState: ExerciseServiceState =
        service?.stateFlow?.collectAsStateWithLifecycle()?.value ?: ExerciseServiceState()
    val inWorkout = exState.isTracking
    val ambient by isAmbient

    // GPS acquisition status (from WHS warm-up) for the preview screen.
    val avail = exState.locationAvailability
    val gpsReady = avail == LocationAvailability.ACQUIRED_TETHERED ||
        avail == LocationAvailability.ACQUIRED_UNTETHERED
    val gpsLabel = when {
        gpsReady -> "GPS ready"
        avail == null || avail == LocationAvailability.ACQUIRING ||
            avail == LocationAvailability.UNKNOWN -> "Acquiring GPS…"
        else -> "No GPS"
    }
    val gpsColor = when {
        gpsReady -> Color(0xFF39FF14)
        avail == null || avail == LocationAvailability.ACQUIRING ||
            avail == LocationAvailability.UNKNOWN -> Color(0xFFF9A825)
        else -> Color(0xFFB00020)
    }

    val context = LocalContext.current
    // Reload when a route is imported/deleted (RouteUpdates ticks) so the list is live.
    val routeVersion by RouteUpdates.version.collectAsStateWithLifecycle()
    val routes by produceState(initialValue = emptyList<GpxRoute>(), context, routeVersion) {
        value = withContext(Dispatchers.IO) { RouteCatalog.loadAll(context) }
    }

    var selectedRoute by remember { mutableStateOf<GpxRoute?>(null) }
    var inPreview by remember { mutableStateOf(false) }
    var wasInWorkout by remember { mutableStateOf(false) }
    var showFinished by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }

    // Workout lifecycle: entering clears prior nav; leaving after a run surfaces
    // the finished summary. selectedRoute is kept so exit animations stay valid.
    LaunchedEffect(inWorkout) {
        if (inWorkout) {
            wasInWorkout = true
            showFinished = false
            inPreview = false
        } else {
            if (wasInWorkout) showFinished = true
            wasInWorkout = false
        }
    }

    // Block app-exit during a run; otherwise back dismisses summary / preview.
    BackHandler(enabled = inWorkout) { /* swallow — use Stop */ }
    BackHandler(enabled = showFinished) { showFinished = false }
    BackHandler(enabled = !inWorkout && !showFinished && inPreview) { inPreview = false }
    BackHandler(enabled = settingsOpen) { settingsOpen = false }

    // Publish the chosen route to the activity so Start (touch or button) can
    // hand it to the navigator.
    LaunchedEffect(selectedRoute) { onRouteChosen(selectedRoute) }

    // Keep the screen always-on only while a run is in progress.
    LaunchedEffect(inWorkout) { onAlwaysOnEnabled(inWorkout) }

    val screen = when {
        inWorkout -> Screen.Activity
        showFinished -> Screen.Finished
        settingsOpen -> Screen.Settings
        inPreview && selectedRoute != null -> Screen.Preview
        else -> Screen.List
    }

    MaterialTheme {
        Scaffold(timeText = { if (!ambient) TimeText() }) {
            Box(Modifier.fillMaxSize()) {
                when {
                    // Always-on: low-power, mostly-black render. WHS keeps recording on
                    // the co-processor; the per-second ticker (in the interactive tree)
                    // is disposed because that tree leaves composition here.
                    ambient -> AmbientScreen(inWorkout, exState, selectedRoute, ambientTick.value)
                    else -> AnimatedContent(
                        targetState = screen,
                        transitionSpec = {
                            // Forward (deeper) slides in from the right; back from the left.
                            val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                            (slideInHorizontally { it * dir } + fadeIn()) togetherWith
                                (slideOutHorizontally { -it * dir } + fadeOut())
                        },
                        label = "nav",
                    ) { target ->
                        when (target) {
                            Screen.Activity -> ActivityScreen(selectedRoute, exState, onLap, onPause, onResume, onStop)
                            Screen.Finished -> FinishedScreen(onDone = { showFinished = false })
                            Screen.Preview -> selectedRoute?.let {
                                PreviewScreen(
                                    route = it,
                                    gpsReady = gpsReady,
                                    gpsLabel = gpsLabel,
                                    gpsColor = gpsColor,
                                    currentLocation = exState.latestLocation,
                                    headingDeg = exState.headingDegrees,
                                    onStart = onStart,
                                    onPrepare = onPrepareRun,
                                    onStopPrepare = onStopPrepare,
                                )
                            }
                            Screen.List -> RouteListScreen(
                                routes = routes,
                                onSelect = { selectedRoute = it; inPreview = true },
                                onOpenSettings = { settingsOpen = true },
                            )
                            Screen.Settings -> SettingsScreen(onBack = { settingsOpen = false })
                        }
                    }
                }

                // Lap summary pops over everything (incl. ambient) when a lap completes.
                lapBanner.value?.let { LapBanner(it) }
            }
        }
    }
}

/** Loads the route's baked basemap (off the main thread); null until ready / if none. */
@Composable
private fun rememberBaseMap(route: GpxRoute?): BaseMap? {
    val context = LocalContext.current
    return produceState<BaseMap?>(initialValue = null, route?.fileName) {
        value = withContext(Dispatchers.IO) { BaseMapStore.load(context, route?.fileName) }
    }.value
}

/** Low-power always-on render. Run screen shows a dim map + stats refreshed ~1/min. */
@Composable
private fun AmbientScreen(
    inWorkout: Boolean,
    state: ExerciseServiceState,
    route: GpxRoute?,
    tick: Int,
) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        if (!inWorkout) {
            Text("WearOsGpx", color = Color.White.copy(alpha = 0.6f))
            return
        }

        val now = remember(tick) { System.currentTimeMillis() }
        // Burn-in: nudge content a few px each ambient update.
        val dx = ((tick % 7) - 3).dp
        val dy = ((tick % 5) - 2).dp

        route?.points?.takeIf { it.isNotEmpty() }?.let { pts ->
            BreadcrumbCanvas(
                route = pts,
                current = state.latestLocation,
                headingDegrees = state.headingDegrees,
                mode = MapMode.FOLLOW,
                viewRadiusMeters = 180f,
                ambient = true,
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(dx, dy)
                .padding(bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Metric("%.2f".format(state.distanceMeters / 1000), "km")
                Metric(formatPace(state.distanceMeters, state.activeDurationMillis), "/km")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Metric(formatElapsed(state.elapsedMillis(now)), "")
                Metric(hrText(state.heartRateBpm), "bpm")
            }
        }
    }
}

/** Navigation destinations, ordered by depth for slide-direction inference. */
private enum class Screen { List, Preview, Activity, Finished, Settings }

/** Scrollable list of available GPX routes. */
@Composable
private fun RouteListScreen(
    routes: List<GpxRoute>,
    onSelect: (GpxRoute) -> Unit,
    onOpenSettings: () -> Unit,
) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = rememberScalingLazyListState(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item { ListHeader { Text("Routes") } }
        if (routes.isEmpty()) {
            item { Text("Loading…", color = Color.White) }
        } else {
            items(routes) { route ->
                Chip(
                    onClick = { onSelect(route) },
                    label = {
                        Text(route.name ?: "Route", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    secondaryLabel = { Text("%.2f km".format(route.totalDistanceMeters / 1000)) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            Chip(
                onClick = onOpenSettings,
                label = { Text("Settings") },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Watch settings — currently the run-display power mode. */
@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(WatchSettings.runDisplayMode(context)) }
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = rememberScalingLazyListState(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item { ListHeader { Text("Run display") } }
        items(RunDisplayMode.entries) { option ->
            val selected = option == mode
            Chip(
                onClick = {
                    WatchSettings.setRunDisplayMode(context, option)
                    mode = option
                },
                label = {
                    Text((if (selected) "● " else "○ ") + option.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                secondaryLabel = { Text(option.blurb, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                colors = if (selected) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Chip(
                onClick = onBack,
                label = { Text("Done") },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private val EDGE_BUTTON_HEIGHT = 60.dp

/**
 * Course preview (sports-watch style): GPS status up top, route + stats, and an
 * edge-hugging Start button that greys out until GPS locks — but stays clickable.
 * Entering the screen warms up GPS; leaving without starting cancels the warm-up.
 */
@Composable
private fun PreviewScreen(
    route: GpxRoute,
    gpsReady: Boolean,
    gpsLabel: String,
    gpsColor: Color,
    currentLocation: GeoPoint?,
    headingDeg: Float?,
    onStart: () -> Unit,
    onPrepare: () -> Unit,
    onStopPrepare: () -> Unit,
) {
    DisposableEffect(Unit) {
        onPrepare()
        onDispose { onStopPrepare() }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            // Reserve space for the edge button so content never sits under it.
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 22.dp, bottom = EDGE_BUTTON_HEIGHT),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GpsStatus(gpsLabel, gpsColor)
            StartProximity(route, currentLocation, headingDeg)
            BreadcrumbCanvas(
                route = route.points,
                mode = MapMode.OVERVIEW,
                routeColor = Color(0xFF39FF14),  // bright course line; no neon track in preview
                baseMap = rememberBaseMap(route),
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            Text(
                text = route.name ?: "Route",
                color = Color.White,
                style = MaterialTheme.typography.title3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(0.75f),
            )
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Metric("%.2f".format(route.totalDistanceMeters / 1000), "km")
                Metric("%.0f".format(route.totalAscentMeters), "m")
            }
        }

        // Greyed until GPS locks, but always clickable (start is allowed before GPS lock).
        EdgeButton(
            text = "Start",
            onClick = onStart,
            color = if (gpsReady) Color(0xFF2E7D32) else Color(0xFF3A3A3C),
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** GPS acquisition pill: colored dot + status text. */
@Composable
private fun GpsStatus(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(label, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.caption2)
    }
}

/**
 * Proximity to the course start (sports-watch style). Tells you whether to walk to the
 * start: "At start" within 30 m, otherwise an arrow + distance pointing to it.
 */
@Composable
private fun StartProximity(route: GpxRoute, current: GeoPoint?, headingDeg: Float?) {
    val start = route.points.firstOrNull() ?: return
    if (current == null) return
    val distance = GeoUtils.haversineMeters(current, start)

    if (distance <= 30.0) {
        Text("● At start", color = Color(0xFF39FF14), style = MaterialTheme.typography.caption2)
    } else {
        val bearing = GeoUtils.bearingDegrees(current, start)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "↑",
                color = Color.White,
                modifier = Modifier.rotate(bearing - (headingDeg ?: 0f)),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "to start · ${formatDistanceShort(distance)}",
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.caption2,
            )
        }
    }
}

/**
 * Flat-topped button whose bottom and sides follow the round screen edge exactly,
 * with no gap. The shape is a circular segment: the bottom arc uses the screen
 * radius (= half the button's full width), so when anchored flush to the bottom
 * it traces the display boundary.
 */
@Composable
private fun EdgeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF2E7D32),
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(EDGE_BUTTON_HEIGHT)
            .clip(BottomEdgeShape)
            .background(color)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.TopCenter,
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.button,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/** Circular-segment outline: flat chord on top, screen-radius arc on the bottom. */
private object BottomEdgeShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = size.width
        val h = size.height
        val r = w / 2f
        val cx = w / 2f
        val cy = h - r                                   // circle center (bottom edge = cy + r = h)
        val halfChord = sqrt((2f * r * h - h * h).coerceAtLeast(0f))
        val start = Math.toDegrees(atan2((0f - cy).toDouble(), halfChord.toDouble())).toFloat()
        val sweep = 180f - 2f * start

        val path = Path().apply {
            moveTo(cx - halfChord, 0f)                   // top-left of flat chord
            lineTo(cx + halfChord, 0f)                   // flat top
            arcTo(Rect(cx - r, cy - r, cx + r, cy + r), start, sweep, forceMoveTo = false)
            close()
        }
        return Outline.Generic(path)
    }
}

/**
 * Active run: a 2-page pager. Page 1 (shown first) is the live Map; swipe right
 * to page 0 for RunControls (Pause / Resume / Stop).
 */
@Composable
private fun ActivityScreen(
    route: GpxRoute?,
    state: ExerciseServiceState,
    onLap: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    val pager = rememberPagerState(initialPage = 1, pageCount = { 2 })
    HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
        if (page == 1) ActiveMap(route, state)
        else RunControls(state, onLap, onPause, onResume, onStop)
    }
}

/** Follow map (track-up, zoomed) with the live stats overlay. */
@Composable
private fun ActiveMap(route: GpxRoute?, state: ExerciseServiceState) {
    val routePoints = route?.points ?: emptyList()

    // The actual recorded track for this run, observed live from Room. Only while
    // the screen is on (collectAsStateWithLifecycle), so it doesn't query in ambient.
    val context = LocalContext.current
    val runId = state.runId
    val trackPoints: List<GeoPoint> = if (runId != null) {
        val repo = remember { RunRepository(WearGpxDatabase.getInstance(context).runDao()) }
        val entities by repo.observeTrackPoints(runId)
            .collectAsStateWithLifecycle(initialValue = emptyList())
        remember(entities) {
            entities.map { GeoPoint(it.latitude, it.longitude, it.altitudeMeters, it.epochMillis) }
        }
    } else {
        emptyList()
    }

    // Until a real fix arrives, fall back to the route start so we stay in the
    // zoomed follow view (never the whole-route overview) during a run.
    val acquiring = state.latestLocation == null
    val current = state.latestLocation ?: trackPoints.lastOrNull() ?: routePoints.firstOrNull()
    // Track-up heading from GPS course-over-ground (only meaningful while moving),
    // falling back to the bearing of the last two recorded points.
    val heading = state.headingDegrees ?: trackPoints.takeIf { it.size >= 2 }
        ?.let { GeoUtils.bearingDegrees(it[it.size - 2], it.last()) }

    // Camera: zoomed out on the route while acquiring, then smoothly zoom in to
    // you (~180 m) when the first fix lands.
    val fitRadius = remember(routePoints) { routeFitRadius(routePoints) }
    val viewRadius = remember { Animatable(fitRadius) }
    LaunchedEffect(acquiring, fitRadius) {
        if (acquiring) viewRadius.snapTo(fitRadius)
        else viewRadius.animateTo(180f, tween(durationMillis = 1400, easing = FastOutSlowInEasing))
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        BreadcrumbCanvas(
            route = routePoints,
            track = trackPoints,
            current = current,
            headingDegrees = heading,
            mode = MapMode.FOLLOW,
            viewRadiusMeters = viewRadius.value,
            baseMap = rememberBaseMap(route),
        )

        // Scrim so the overlay stays legible over the map.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.5f)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))
                    )
                )
        )

        // 2×2 live stats with icons. Fixed-width cells (never shift); lifted into the
        // circle's wider band so the round screen doesn't clip the columns.
        // One live clock drives both the time and pace cells, so pace shows as soon
        // as there's distance + time (instead of waiting on the raw WHS checkpoint).
        val elapsed = rememberLiveElapsedMillis(state)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                StatCell(R.drawable.ic_stat_distance, "%.2f".format(state.distanceMeters / 1000), "km")
                StatCell(R.drawable.ic_stat_pace, formatPace(state.distanceMeters, elapsed), "/km")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                StatCell(R.drawable.ic_stat_time, formatElapsed(elapsed), "")
                StatCell(R.drawable.ic_stat_hr, hrText(state.heartRateBpm), "bpm")
            }
        }

        if (acquiring) {
            Box(
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = 26.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                CuePill(
                    background = Color(0xFFF9A825),
                    title = "ACQUIRING GPS",
                    subtitle = "hold on…",
                    arrowDeg = null,
                )
            }
        } else {
            NavigationCue(
                nav = state.nav,
                headingDeg = state.headingDegrees,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

/**
 * Top-of-screen navigation cue. Priority: off-course warning (red, with an arrow
 * pointing back to the route) → upcoming turn (amber) → distance remaining.
 */
@Composable
private fun NavigationCue(nav: NavProgress?, headingDeg: Float?, modifier: Modifier = Modifier) {
    if (nav == null) return
    Box(
        modifier = modifier.fillMaxWidth().padding(top = 26.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        when {
            nav.isOffCourse -> CuePill(
                background = Color(0xFFB00020),
                title = "OFF COURSE",
                subtitle = "${formatDistanceShort(nav.crossTrackMeters)} · rejoin",
                arrowDeg = nav.bearingToRouteDeg - (headingDeg ?: 0f),
            )
            nav.nextTurn != null -> TurnPrompt(nav.nextTurn!!)
            else -> Text(
                text = "${"%.2f".format(nav.distanceRemainingMeters / 1000)} km to go",
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.caption1,
            )
        }
    }
}

/** Prominent turn-by-turn prompt: big directional arrow + "Turn left/right" + distance. */
@Composable
private fun TurnPrompt(turn: UpcomingTurn) {
    val left = turn.direction == TurnDirection.LEFT
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFFF9A825))
            .padding(horizontal = 20.dp, vertical = 6.dp),
    ) {
        Text(
            if (left) "↰" else "↱",
            color = Color.Black,
            style = MaterialTheme.typography.display3,
        )
        Text(
            if (left) "Turn left" else "Turn right",
            color = Color.Black,
            style = MaterialTheme.typography.title3,
        )
        Text(
            "${turn.distanceMeters.roundToInt()} m",
            color = Color.Black.copy(alpha = 0.75f),
            style = MaterialTheme.typography.caption1,
        )
    }
}

@Composable
private fun CuePill(background: Color, title: String, subtitle: String, arrowDeg: Float?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .padding(horizontal = 14.dp, vertical = 3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (arrowDeg != null) {
                Text(
                    "↑",
                    color = Color.White,
                    style = MaterialTheme.typography.title3,
                    modifier = Modifier.rotate(arrowDeg),
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(title, color = Color.White, style = MaterialTheme.typography.caption1, maxLines = 1)
        }
        Text(
            subtitle,
            color = Color.White.copy(alpha = 0.9f),
            style = MaterialTheme.typography.caption2,
            maxLines = 1,
        )
    }
}

/** Pause / Resume / Stop, reached by swiping right from the map. */
@Composable
private fun RunControls(
    state: ExerciseServiceState,
    onLap: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Metric("%.2f".format(state.distanceMeters / 1000), "km")
            Metric(formatPace(state.distanceMeters, state.activeDurationMillis), "/km")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Metric(formatElapsed(rememberLiveElapsedMillis(state)), "")
            Metric(hrText(state.heartRateBpm), "bpm")
        }
        // Back-to-start: arrow points to the route start relative to heading.
        state.nav?.let { nav ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "↑",
                    color = Color.White,
                    modifier = Modifier.rotate(nav.bearingToStartDeg - (state.headingDegrees ?: 0f)),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "to start · ${formatDistanceShort(nav.distanceToStartMeters)}",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.caption2,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        if (state.exerciseState?.isPaused == true) {
            PillButton("Resume", onResume, Color(0xFF2E7D32))
        } else {
            PillButton("Pause", onPause, Color(0xFF3A3A3C))
        }
        PillButton("Lap", onLap, Color(0xFF1E4E79))
        PillButton("Stop", onStop, Color(0xFFB00020))
    }
}

/** One split row in the finished summary: lap #, distance, time, pace. */
@Composable
private fun SplitRow(lap: LapEntity) {
    Row(
        modifier = Modifier.fillMaxWidth(0.82f).padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("${lap.lapNumber}", color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.caption2)
        Text("%.2f km".format(lap.distanceMeters / 1000), color = Color.White,
            style = MaterialTheme.typography.caption1)
        Text(formatElapsed(lap.durationMillis), color = Color.White,
            style = MaterialTheme.typography.caption1)
        Text("${formatPace(lap.distanceMeters, lap.durationMillis)}/km", color = Color.White,
            style = MaterialTheme.typography.caption1)
    }
}

/** Transient lap summary, shown over everything when a lap completes. */
@Composable
private fun LapBanner(lap: LapEntity) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.82f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (lap.isManual) "LAP ${lap.lapNumber} (manual)" else "LAP ${lap.lapNumber}",
                color = Color(0xFF39FF14),
                style = MaterialTheme.typography.title2,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Metric(formatElapsed(lap.durationMillis), "")
                Metric(formatPace(lap.distanceMeters, lap.durationMillis), "/km")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Metric("%.2f".format(lap.distanceMeters / 1000), "km")
                Metric(hrText(lap.avgHeartRateBpm), "bpm")
            }
        }
    }
}

/** Full-width rounded pill button with a centered single-line label. */
@Composable
private fun PillButton(text: String, onClick: () -> Unit, backgroundColor: Color) {
    Chip(
        onClick = onClick,
        colors = ChipDefaults.chipColors(
            backgroundColor = backgroundColor,
            contentColor = Color.White,
        ),
        label = {
            Text(
                text,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        },
        modifier = Modifier.fillMaxWidth(0.68f),
    )
}

/** Post-run summary, read from the finalized run in Room (survives the service stopping). */
@Composable
private fun FinishedScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { RunRepository(WearGpxDatabase.getInstance(context).runDao()) }
    val run by repo.observeMostRecentCompletedRun().collectAsStateWithLifecycle(initialValue = null)

    Box(Modifier.fillMaxSize()) {
        val r = run
        if (r == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Saving…") }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 28.dp, bottom = EDGE_BUTTON_HEIGHT + 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Run complete", color = Color.White, style = MaterialTheme.typography.title3)
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Metric("%.2f".format(r.totalDistanceMeters / 1000), "km")
                    Metric(formatElapsed(r.totalDurationMillis), "")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Metric(formatPace(r.totalDistanceMeters, r.totalDurationMillis), "/km")
                    Metric("%.0f".format(r.totalAscentMeters), "m")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Metric(hrText(r.avgHeartRateBpm), "avg")
                    Metric(hrText(r.maxHeartRateBpm), "max")
                }

                val laps by repo.observeLaps(r.runId)
                    .collectAsStateWithLifecycle(initialValue = emptyList())
                if (laps.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("Splits", color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.caption1)
                    laps.forEach { lap -> SplitRow(lap) }
                }
            }
        }

        EdgeButton(
            text = "Done",
            onClick = onDone,
            color = Color(0xFF37474F),
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** Fixed-width stat with an icon — width is constant so values never shift the row. */
@Composable
private fun StatCell(iconRes: Int, value: String, unit: String) {
    Row(
        modifier = Modifier.width(84.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = Color(0xFF39FF14),
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(value, color = Color.White, style = MaterialTheme.typography.title3, maxLines = 1)
        if (unit.isNotEmpty()) {
            Text(
                " $unit",
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.caption2,
            )
        }
    }
}

@Composable
private fun Metric(value: String, unit: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, color = Color.White, style = MaterialTheme.typography.title2)
        if (unit.isNotEmpty()) {
            Text(
                " $unit",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.caption1,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
    }
}

/**
 * Live, ticking elapsed time. WHS only gives a per-batch duration checkpoint, so
 * we extrapolate with a 1s tick. The tick is gated to [Lifecycle.State.STARTED]
 * so it stops when the screen is off/ambient — no waking the app processor.
 */
@Composable
private fun rememberLiveElapsedMillis(state: ExerciseServiceState): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(state.exerciseState, lifecycleOwner) {
        if (state.exerciseState != ExerciseState.ACTIVE) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1_000)
            }
        }
    }
    return state.elapsedMillis(now)
}

/** mm:ss, or h:mm:ss past an hour. */
private fun formatElapsed(millis: Long): String {
    val totalSec = millis / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Average pace as min:sec per km, or "--:--" before enough distance is logged. */
private fun formatPace(distanceMeters: Double, durationMillis: Long): String {
    if (distanceMeters < 20.0 || durationMillis <= 0L) return "--:--"
    val secPerKm = (durationMillis / 1000.0) / (distanceMeters / 1000.0)
    if (secPerKm.isInfinite() || secPerKm > 5_999) return "--:--"
    return "%d:%02d".format((secPerKm / 60).toInt(), (secPerKm % 60).toInt())
}

private fun hrText(bpm: Double?): String = bpm?.let { "%.0f".format(it) } ?: "--"

private fun formatDistanceShort(meters: Double): String =
    if (meters < 1000) "${meters.roundToInt()} m" else "%.2f km".format(meters / 1000)

/** A view radius (meters) that roughly frames the whole route — the zoom-in start. */
private fun routeFitRadius(points: List<GeoPoint>): Float {
    if (points.size < 2) return 180f
    val origin = points.first()
    val mLat = 111_320.0
    val mLon = 111_320.0 * kotlin.math.cos(Math.toRadians(origin.latitude))
    var maxMeters = 0.0
    for (p in points) {
        val east = (p.longitude - origin.longitude) * mLon
        val north = (p.latitude - origin.latitude) * mLat
        maxMeters = maxOf(maxMeters, kotlin.math.hypot(east, north))
    }
    return (maxMeters * 0.7 + 60.0).toFloat().coerceIn(300f, 2500f)
}
