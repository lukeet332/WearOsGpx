package com.wearosgpx.service

import androidx.health.services.client.data.ExerciseState
import androidx.health.services.client.data.LocationAvailability
import com.wearosgpx.data.gpx.GeoPoint
import com.wearosgpx.navigation.NavProgress

/**
 * Snapshot of the live workout, observed by the UI (Phase 3). Updated on every
 * Health Services batch. Immutable so Compose recomposes cleanly.
 */
data class ExerciseServiceState(
    val exerciseState: ExerciseState? = null,
    /** True once a real run has started (distinguishes a running session from GPS warm-up). */
    val isTracking: Boolean = false,
    val runId: Long? = null,
    val startEpochMillis: Long = 0L,

    val distanceMeters: Double = 0.0,
    /** Active duration as of [durationCheckpointEpochMillis] — a snapshot, not a live clock. */
    val activeDurationMillis: Long = 0L,
    val durationCheckpointEpochMillis: Long? = null,
    val heartRateBpm: Double? = null,
    val speedMetersPerSec: Double? = null,
    val totalCalories: Double? = null,

    val latestLocation: GeoPoint? = null,
    /** Course over ground, degrees clockwise from north; for track-up follow mode. */
    val headingDegrees: Float? = null,
    val locationAvailability: LocationAvailability? = null,

    /** Route-following progress; null when no route is loaded for the run. */
    val nav: NavProgress? = null,

    val error: String? = null,
) {
    val isActive: Boolean
        get() = exerciseState == ExerciseState.ACTIVE

    /**
     * Live elapsed time for the given wall-clock [nowEpochMillis]. WHS only sends
     * a duration *checkpoint* per batch, so while ACTIVE we extrapolate from it
     * (or from the run start if no checkpoint has arrived yet); when paused/ended
     * we freeze at the last checkpoint.
     */
    fun elapsedMillis(nowEpochMillis: Long): Long = when {
        isActive && durationCheckpointEpochMillis != null ->
            activeDurationMillis + (nowEpochMillis - durationCheckpointEpochMillis).coerceAtLeast(0)
        isActive && startEpochMillis > 0L ->
            (nowEpochMillis - startEpochMillis).coerceAtLeast(0)
        else -> activeDurationMillis
    }
}
