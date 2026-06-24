package com.wearosgpx.mobile.sync

import kotlinx.serialization.Serializable

/**
 * Wire format for a completed run received from the watch.
 *
 * IMPORTANT: must match `com.wearosgpx.sync.RunPayload` in the `:wear` module
 * field-for-field — they are the two ends of the same serialized payload.
 */
@Serializable
data class RunPayload(
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val totalDistanceMeters: Double,
    val totalDurationMillis: Long,
    val totalAscentMeters: Double,
    val avgHeartRateBpm: Double? = null,
    val maxHeartRateBpm: Double? = null,
    val title: String = "Run",
    val points: List<PayloadPoint> = emptyList(),
)

@Serializable
data class PayloadPoint(
    val lat: Double,
    val lon: Double,
    val ele: Double? = null,
    val epochMillis: Long,
    val hr: Double? = null,
)
