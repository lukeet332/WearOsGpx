package com.wearosgpx.mobile.health

import com.wearosgpx.mobile.sync.PayloadPoint

/**
 * Pure preparation of run points for Health Connect — extracted from
 * [HealthConnectWriter] so it can be unit-tested without the HC client.
 *
 * This is exactly where run sync silently broke before: HC's `ExerciseRoute` and
 * `HeartRateRecord` both reject duplicate/out-of-order timestamps, and
 * `HeartRateRecord.Sample` rejects beatsPerMinute outside 1..300 (WHS emits 0-bpm
 * sensor-gap samples). These functions enforce both invariants.
 */
internal object HealthConnectPrep {

    /** Points sorted by time with duplicate-timestamp points dropped (strictly increasing). */
    fun orderedDistinctPoints(points: List<PayloadPoint>): List<PayloadPoint> =
        points.sortedBy { it.epochMillis }.distinctBy { it.epochMillis }

    data class HrSample(val epochMillis: Long, val bpm: Long)

    /** Valid heart-rate samples only: drop null/<1 bpm, clamp to 1..300, strictly increasing time. */
    fun heartRateSamples(points: List<PayloadPoint>): List<HrSample> =
        orderedDistinctPoints(points)
            .filter { (it.hr ?: 0.0) >= 1.0 }
            .map { HrSample(it.epochMillis, it.hr!!.toLong().coerceIn(1, 300)) }
}
