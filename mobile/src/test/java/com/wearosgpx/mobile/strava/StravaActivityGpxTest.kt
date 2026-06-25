package com.wearosgpx.mobile.strava

import com.wearosgpx.mobile.sync.PayloadPoint
import com.wearosgpx.mobile.sync.RunPayload
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Strava treats a GPX as an *activity* (not a route) only if points carry <time>,
 * and reads HR from the TrackPointExtension. Guard that the built GPX has both, so
 * a future tweak can't silently turn uploads back into routes / drop heart rate.
 */
class StravaActivityGpxTest {

    private fun payload() = RunPayload(
        startEpochMillis = 1_000_000,
        endEpochMillis = 1_060_000,
        totalDistanceMeters = 100.0,
        totalDurationMillis = 60_000,
        totalAscentMeters = 0.0,
        title = "Morning Run",
        points = listOf(
            PayloadPoint(lat = 51.5, lon = -0.1, epochMillis = 1_000_000, hr = 140.0),
            PayloadPoint(lat = 51.5009, lon = -0.1, epochMillis = 1_030_000, hr = 0.0),  // 0 bpm → no hr tag
        ),
    )

    @Test
    fun build_emitsRunTrackWithTimesAndHeartRate() {
        val gpx = StravaActivityGpx.build(payload())
        assertTrue("is a running track", gpx.contains("<type>running</type>"))
        assertTrue("has track points", gpx.contains("<trkpt"))
        assertTrue("has per-point time (so Strava treats it as an activity)", gpx.contains("<time>"))
        assertTrue("embeds heart rate via TrackPointExtension", gpx.contains("<gpxtpx:hr>140</gpxtpx:hr>"))
        assertTrue("escapes/keeps the title", gpx.contains("<name>Morning Run</name>"))
    }

    @Test
    fun build_omitsHeartRateForZeroBpmSamples() {
        val gpx = StravaActivityGpx.build(payload())
        // Only the 140-bpm point should carry an hr tag (the 0-bpm one is dropped).
        assertTrue(gpx.split("<gpxtpx:hr>").size - 1 == 1)
    }
}
