package com.wearosgpx.mobile.sync

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.wearosgpx.mobile.health.HealthConnectWriter
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.zip.GZIPInputStream

/**
 * Imports finished runs from the watch into Health Connect.
 *
 * Used from two places so a run is never lost:
 *  - [RunSyncListenerService] when the Data Layer pushes a run while the phone is awake;
 *  - [importQueued] on every app open, which scans the Data Layer for runs that are
 *    still queued (e.g. the phone was asleep / the service missed the event) and
 *    writes any not already imported.
 *
 * Writes are de-duplicated by run start time (persisted in SharedPreferences), so
 * the listener and the catch-up scan can't double-insert the same run.
 */
object RunImporter {

    private const val TAG = "RunImport"
    private const val PREFS = "run_import"
    private const val KEY_DONE = "processed_starts"
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Write a run to Health Connect if it isn't there yet (de-duped by start time).
     * Health Connect is the single sync target — OHealth reads from it. Returns true if
     * anything new was written.
     */
    suspend fun importPayload(context: Context, payload: RunPayload, trigger: String): Boolean {
        val key = payload.startEpochMillis.toString()
        Log.i(
            TAG,
            "[$trigger] run start=$key pts=${payload.points.size} " +
                "hrPts=${payload.points.count { it.hr != null }} dist=${payload.totalDistanceMeters} " +
                "dur=${payload.endEpochMillis - payload.startEpochMillis}ms",
        )
        var didSomething = false

        // Health Connect.
        val writer = HealthConnectWriter(context)
        when {
            !writer.isAvailable() -> Log.w(TAG, "[$trigger] Health Connect unavailable.")
            !writer.hasAllPermissions() -> Log.w(TAG, "[$trigger] HC write permission missing.")
            processed(context).contains(key) -> Log.i(TAG, "[$trigger] run $key already in Health Connect.")
            else -> runCatching {
                writer.write(payload)
                markProcessed(context, key)
                Log.i(TAG, "[$trigger] WROTE run $key to Health Connect ✓")
                didSomething = true
            }.onFailure { Log.e(TAG, "[$trigger] HC import FAILED for run $key", it) }
        }

        return didSomething
    }

    /** Scan the Data Layer for queued /run items and import any not yet written. Returns count newly written. */
    suspend fun importQueued(context: Context, trigger: String): Int {
        val dataClient = Wearable.getDataClient(context)
        val buffer = runCatching { dataClient.getDataItems().await() }.getOrElse {
            Log.e(TAG, "[$trigger] getDataItems failed", it); return 0
        }
        val runItems = buffer.filter { it.uri.path?.startsWith("/run") == true }
        Log.i(TAG, "[$trigger] queued /run items: ${runItems.size} (of ${buffer.count} total data items)")
        var written = 0
        for (item in runItems) {
            val asset = DataMapItem.fromDataItem(item).dataMap.getAsset("payload")
            if (asset == null) { Log.w(TAG, "[$trigger] ${item.uri.path}: no payload asset"); continue }
            runCatching {
                val bytes = readAsset(context, asset)
                val payload = json.decodeFromString<RunPayload>(ungzip(bytes).toString(Charsets.UTF_8))
                if (importPayload(context, payload, trigger)) written++
            }.onFailure { Log.e(TAG, "[$trigger] failed reading ${item.uri.path}", it) }
        }
        buffer.release()
        Log.i(TAG, "[$trigger] import complete: $written newly written.")
        return written
    }

    /**
     * Debug aid: dump recent Health Connect exercise sessions so we can compare the
     * format of runs we wrote against ones written by the native OnePlus tracker.
     * Best-effort — silently does nothing if READ permission isn't granted.
     */
    suspend fun logRecentSessions(context: Context) {
        runCatching {
            val client = HealthConnectClient.getOrCreate(context)
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = ExerciseSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(
                        Instant.now().minus(30, ChronoUnit.DAYS), Instant.now(),
                    ),
                ),
            )
            Log.i(TAG, "HC exercise sessions (last 30d): ${response.records.size}")
            response.records.forEach { r ->
                val route = when (val rr = r.exerciseRouteResult) {
                    is ExerciseRouteResult.Data -> "route(${rr.exerciseRoute.route.size} pts)"
                    is ExerciseRouteResult.ConsentRequired -> "route(consent-required)"
                    is ExerciseRouteResult.NoData -> "no-route"
                    else -> "route(?)"
                }
                Log.i(
                    TAG,
                    "  • type=${r.exerciseType} title=${r.title} " +
                        "start=${r.startTime} end=${r.endTime} " +
                        "origin=${r.metadata.dataOrigin.packageName} $route " +
                        "device=${r.metadata.device?.manufacturer}/${r.metadata.device?.model} " +
                        "id=${r.metadata.id}",
                )
            }
        }.onFailure { Log.w(TAG, "readRecords failed (READ permission not granted?): ${it.message}") }
    }

    // --- helpers ---

    private fun processed(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_DONE, emptySet())?.toSet() ?: emptySet()

    private fun markProcessed(context: Context, key: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val updated = (prefs.getStringSet(KEY_DONE, emptySet())?.toMutableSet() ?: mutableSetOf())
        updated.add(key)
        prefs.edit().putStringSet(KEY_DONE, updated).apply()
    }

    private suspend fun readAsset(context: Context, asset: Asset): ByteArray {
        val response = Wearable.getDataClient(context).getFdForAsset(asset).await()
        return response.inputStream.use { it.readBytes() }
    }

    private fun ungzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPInputStream(data.inputStream()).use { it.copyTo(out) }
        return out.toByteArray()
    }
}
