package com.wearosgpx.presentation

import kotlin.math.roundToInt

/**
 * Pure display formatters for the live/finished stats. Extracted from the UI so
 * they're independently unit-tested (these are easy to break accidentally — the
 * pace cell once silently showed "--:--" for a whole run).
 */

/** Elapsed time as mm:ss, or h:mm:ss once past an hour. */
internal fun formatElapsed(millis: Long): String {
    val totalSec = (millis / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Average pace as min:sec per km, or "--:--" before enough distance/time is logged. */
internal fun formatPace(distanceMeters: Double, durationMillis: Long): String {
    if (distanceMeters < 20.0 || durationMillis <= 0L) return "--:--"
    val secPerKm = (durationMillis / 1000.0) / (distanceMeters / 1000.0)
    if (secPerKm.isInfinite() || secPerKm > 5_999) return "--:--"
    return "%d:%02d".format((secPerKm / 60).toInt(), (secPerKm % 60).toInt())
}

internal fun hrText(bpm: Double?): String = bpm?.let { "%.0f".format(it) } ?: "--"

internal fun formatDistanceShort(meters: Double): String =
    if (meters < 1000) "${meters.roundToInt()} m" else "%.2f km".format(meters / 1000)
