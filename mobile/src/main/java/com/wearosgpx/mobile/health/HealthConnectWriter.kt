package com.wearosgpx.mobile.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Length
import com.wearosgpx.mobile.sync.RunPayload
import java.time.Instant
import java.time.ZoneId

/**
 * Writes a received run into Health Connect as an [ExerciseSessionRecord] (with
 * the GPS [ExerciseRoute]) plus [DistanceRecord] and [HeartRateRecord]. OHealth
 * then reads these from Health Connect.
 */
class HealthConnectWriter(private val context: Context) {

    fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    /** True once the core write permissions are granted. The route permission is
     *  best-effort (Health Connect grants it via a separate flow and may not report
     *  it here), so we don't gate on it — the writer just omits the route if absent. */
    suspend fun hasAllPermissions(): Boolean {
        val client = HealthConnectClient.getOrCreate(context)
        return client.permissionController.getGrantedPermissions().containsAll(CORE_PERMISSIONS)
    }

    suspend fun write(payload: RunPayload) {
        val client = HealthConnectClient.getOrCreate(context)

        val start = Instant.ofEpochMilli(payload.startEpochMillis)
        val end = Instant.ofEpochMilli(payload.endEpochMillis)
        val zone = ZoneId.systemDefault()
        val startOffset = zone.rules.getOffset(start)
        val endOffset = zone.rules.getOffset(end)

        val routeLocations = payload.points.map { p ->
            ExerciseRoute.Location(
                time = Instant.ofEpochMilli(p.epochMillis),
                latitude = p.lat,
                longitude = p.lon,
                altitude = p.ele?.let { Length.meters(it) },
            )
        }

        val records = mutableListOf<Record>()

        records += ExerciseSessionRecord(
            startTime = start,
            startZoneOffset = startOffset,
            endTime = end,
            endZoneOffset = endOffset,
            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            title = payload.title,
            exerciseRoute = if (routeLocations.size >= 2) ExerciseRoute(routeLocations) else null,
            metadata = Metadata(),
        )

        if (payload.totalDistanceMeters > 0) {
            records += DistanceRecord(
                startTime = start,
                startZoneOffset = startOffset,
                endTime = end,
                endZoneOffset = endOffset,
                distance = Length.meters(payload.totalDistanceMeters),
                metadata = Metadata(),
            )
        }

        val hrSamples = payload.points
            .filter { it.hr != null }
            .map { HeartRateRecord.Sample(Instant.ofEpochMilli(it.epochMillis), it.hr!!.toLong()) }
        if (hrSamples.isNotEmpty()) {
            records += HeartRateRecord(
                startTime = start,
                startZoneOffset = startOffset,
                endTime = end,
                endZoneOffset = endOffset,
                samples = hrSamples,
                metadata = Metadata(),
            )
        }

        client.insertRecords(records)
    }

    companion object {
        /** Core permissions required to write a run (gate on these). */
        val CORE_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getWritePermission(ExerciseSessionRecord::class),
            HealthPermission.getWritePermission(DistanceRecord::class),
            HealthPermission.getWritePermission(HeartRateRecord::class),
        )

        /** Everything we request — core + the best-effort exercise-route grant. */
        val PERMISSIONS: Set<String> = CORE_PERMISSIONS + "android.permission.health.WRITE_EXERCISE_ROUTE"
    }
}
