package com.wearosgpx.mobile.health

import com.wearosgpx.mobile.sync.PayloadPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the Health Connect point-prep — this is the exact logic that once silently
 * broke run sync (duplicate-timestamp route points crashed ExerciseRoute, and 0-bpm
 * samples crashed HeartRateRecord).
 */
class HealthConnectPrepTest {

    private fun pt(ts: Long, hr: Double? = null) =
        PayloadPoint(lat = 0.0, lon = 0.0, epochMillis = ts, hr = hr)

    @Test
    fun orderedDistinctPoints_sortsAndDropsDuplicateTimestamps() {
        val out = HealthConnectPrep.orderedDistinctPoints(
            listOf(pt(300), pt(100), pt(100), pt(200)),
        )
        assertEquals(listOf(100L, 200L, 300L), out.map { it.epochMillis })
        for (i in 1..out.lastIndex) {
            assertTrue("timestamps must be strictly increasing", out[i].epochMillis > out[i - 1].epochMillis)
        }
    }

    @Test
    fun heartRateSamples_dropInvalid_clampHigh_dedupe() {
        val out = HealthConnectPrep.heartRateSamples(
            listOf(
                pt(100, hr = 0.0),     // sensor gap → dropped
                pt(200, hr = null),    // missing → dropped
                pt(300, hr = 140.0),   // kept
                pt(400, hr = 500.0),   // clamped to 300
                pt(300, hr = 150.0),   // duplicate timestamp → dropped (first wins)
            ),
        )
        assertEquals(listOf(300L, 400L), out.map { it.epochMillis })
        assertEquals(listOf(140L, 300L), out.map { it.bpm })
        out.forEach { assertTrue(it.bpm in 1..300) }
    }
}
