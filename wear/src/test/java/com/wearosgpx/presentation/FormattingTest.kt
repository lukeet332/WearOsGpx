package com.wearosgpx.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure display formatters — easy to break accidentally, so pinned by tests. */
class FormattingTest {

    @Test
    fun pace_knownValues() {
        assertEquals("5:00", formatPace(distanceMeters = 1000.0, durationMillis = 300_000))   // 5:00/km
        assertEquals("4:30", formatPace(distanceMeters = 1000.0, durationMillis = 270_000))   // 4:30/km
        assertEquals("6:00", formatPace(distanceMeters = 500.0, durationMillis = 180_000))    // 3 min / 0.5 km
    }

    @Test
    fun pace_guardsTooLittleData() {
        assertEquals("--:--", formatPace(distanceMeters = 10.0, durationMillis = 60_000))     // < 20 m
        assertEquals("--:--", formatPace(distanceMeters = 1000.0, durationMillis = 0))        // no time
        assertEquals("--:--", formatPace(distanceMeters = 1.0, durationMillis = 9_000_000))   // absurdly slow
    }

    @Test
    fun elapsed_minutesAndHours() {
        assertEquals("0:00", formatElapsed(0))
        assertEquals("1:05", formatElapsed(65_000))
        assertEquals("1:01:01", formatElapsed(3_661_000))
        assertEquals("0:00", formatElapsed(-5_000))   // never negative
    }

    @Test
    fun hr_text() {
        assertEquals("--", hrText(null))
        assertEquals("142", hrText(142.4))
        assertEquals("60", hrText(60.0))
    }

    @Test
    fun distanceShort_metersThenKm() {
        assertEquals("500 m", formatDistanceShort(500.0))
        assertEquals("999 m", formatDistanceShort(999.4))
        assertEquals("1.50 km", formatDistanceShort(1500.0))
    }
}
