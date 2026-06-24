package com.wearosgpx.data.gpx

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A single geographic sample. Used both for a planned route loaded from a GPX
 * file (to navigate / draw the breadcrumb) and as the in-memory shape of a
 * recorded point before it is persisted to Room.
 *
 * elevationMeters / epochMillis are nullable because not every GPX point carries
 * <ele> or <time>.
 */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double? = null,
    val epochMillis: Long? = null,
)

/**
 * A planned route parsed from a .gpx file — the line you follow during a run.
 */
data class GpxRoute(
    val name: String?,
    val points: List<GeoPoint>,
) {
    /** Total path length in meters (sum of haversine segments). */
    val totalDistanceMeters: Double by lazy {
        var sum = 0.0
        for (i in 1 until points.size) {
            sum += GeoUtils.haversineMeters(points[i - 1], points[i])
        }
        sum
    }

    /** Cumulative positive elevation gain in meters, ignoring missing <ele>. */
    val totalAscentMeters: Double by lazy {
        var gain = 0.0
        var prev: Double? = null
        for (p in points) {
            val ele = p.elevationMeters ?: continue
            prev?.let { if (ele > it) gain += ele - it }
            prev = ele
        }
        gain
    }
}

object GeoUtils {
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    /** Great-circle distance between two points, in meters. */
    fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)

        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_METERS * atan2(sqrt(h), sqrt(1 - h))
    }

    /**
     * Initial bearing from [from] to [to], in degrees clockwise from true north
     * (0 = N, 90 = E). Used to derive a heading for track-up follow mode when no
     * GPS course-over-ground is available (e.g. replaying a recorded track).
     */
    fun bearingDegrees(from: GeoPoint, to: GeoPoint): Float {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLon = Math.toRadians(to.longitude - from.longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val deg = Math.toDegrees(atan2(y, x))
        return ((deg + 360.0) % 360.0).toFloat()
    }
}
