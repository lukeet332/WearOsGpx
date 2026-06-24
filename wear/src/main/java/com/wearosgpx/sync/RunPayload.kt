package com.wearosgpx.sync

import kotlinx.serialization.Serializable

/**
 * The completed-run payload sent watch → phone over the Wearable Data Layer.
 *
 * IMPORTANT: this class is duplicated verbatim in the `:mobile` module
 * (`com.wearosgpx.mobile.sync.RunPayload`). Keep the two in sync — they're the
 * wire format. (A shared module would remove the duplication; deferred for now.)
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
