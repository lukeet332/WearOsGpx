package com.wearosgpx.navigation

import com.wearosgpx.data.gpx.GeoPoint
import com.wearosgpx.data.gpx.GeoUtils
import kotlin.math.cos
import kotlin.math.hypot

enum class TurnDirection { LEFT, RIGHT }

/** A turn ahead on the route (within the look-ahead window). */
data class UpcomingTurn(
    val distanceMeters: Double,
    val direction: TurnDirection,
    val angleDeg: Double,
)

/** Where the runner is relative to the planned route, recomputed each fix. */
data class NavProgress(
    val distanceAlongMeters: Double,
    val distanceRemainingMeters: Double,
    val fractionComplete: Float,
    val crossTrackMeters: Double,
    val isOffCourse: Boolean,
    /** Bearing from current position to the nearest point on the route (how to rejoin). */
    val bearingToRouteDeg: Float,
    val distanceToStartMeters: Double,
    val bearingToStartDeg: Float,
    val nextTurn: UpcomingTurn?,
)

/**
 * Follows a planned GPX route. Precomputes per-vertex cumulative distance and the
 * route's significant turns once; then [update] snaps a live position onto the
 * polyline to derive off-course state, progress, and the next turn.
 *
 * Geometry uses a local equirectangular projection (meters east/north) which is
 * accurate over the few-km scale of a route and keeps point-to-segment math simple.
 */
class RouteNavigator(
    private val points: List<GeoPoint>,
    private val offCourseEnterMeters: Double = 40.0,
    private val offCourseExitMeters: Double = 25.0,
    private val turnLookaheadMeters: Double = 120.0,
    private val turnMinAngleDeg: Double = 50.0,   // only proper bends, not gentle curves
) {
    private val cumDist: DoubleArray
    private val segLen: DoubleArray
    val totalDistanceMeters: Double
    private val turns: List<TurnInternal>

    private data class TurnInternal(val atDistance: Double, val direction: TurnDirection, val angle: Double)

    val isUsable: Boolean get() = points.size >= 2

    init {
        val n = points.size
        cumDist = DoubleArray(n)
        segLen = DoubleArray(if (n > 0) n - 1 else 0)
        var acc = 0.0
        for (i in 1 until n) {
            val d = GeoUtils.haversineMeters(points[i - 1], points[i])
            segLen[i - 1] = d
            acc += d
            cumDist[i] = acc
        }
        totalDistanceMeters = acc
        turns = if (n >= 3) detectTurns() else emptyList()
    }

    fun update(current: GeoPoint, previousOffCourse: Boolean): NavProgress {
        // Find the nearest segment and the along-track distance at the projection.
        var bestDist = Double.MAX_VALUE
        var bestAlong = 0.0
        var bestNear = points.first()

        for (i in 0 until segLen.size) {
            val a = points[i]
            val b = points[i + 1]
            // Local meters relative to `a`.
            val mLat = 111_320.0
            val mLon = 111_320.0 * cos(Math.toRadians(a.latitude))
            val bx = (b.longitude - a.longitude) * mLon
            val by = (b.latitude - a.latitude) * mLat
            val px = (current.longitude - a.longitude) * mLon
            val py = (current.latitude - a.latitude) * mLat

            val segSq = bx * bx + by * by
            val t = if (segSq <= 1e-9) 0.0 else ((px * bx + py * by) / segSq).coerceIn(0.0, 1.0)
            val projX = t * bx
            val projY = t * by
            val dist = hypot(px - projX, py - projY)

            if (dist < bestDist) {
                bestDist = dist
                bestAlong = cumDist[i] + t * segLen[i]
                bestNear = GeoPoint(
                    latitude = a.latitude + t * (b.latitude - a.latitude),
                    longitude = a.longitude + t * (b.longitude - a.longitude),
                )
            }
        }

        // Hysteresis: harder to enter off-course than to clear it (avoids flapping).
        val isOff = if (previousOffCourse) bestDist > offCourseExitMeters
        else bestDist > offCourseEnterMeters

        val remaining = (totalDistanceMeters - bestAlong).coerceAtLeast(0.0)
        val fraction = if (totalDistanceMeters > 0) (bestAlong / totalDistanceMeters).toFloat() else 0f

        val next = turns.firstOrNull { it.atDistance > bestAlong + 1.0 }
            ?.takeIf { it.atDistance - bestAlong <= turnLookaheadMeters }
            ?.let { UpcomingTurn(it.atDistance - bestAlong, it.direction, it.angle) }

        return NavProgress(
            distanceAlongMeters = bestAlong,
            distanceRemainingMeters = remaining,
            fractionComplete = fraction.coerceIn(0f, 1f),
            crossTrackMeters = bestDist,
            isOffCourse = isOff,
            bearingToRouteDeg = GeoUtils.bearingDegrees(current, bestNear),
            distanceToStartMeters = GeoUtils.haversineMeters(current, points.first()),
            bearingToStartDeg = GeoUtils.bearingDegrees(current, points.first()),
            nextTurn = next,
        )
    }

    /**
     * Significant turns from route geometry: at each vertex compare the incoming
     * and outgoing bearing using neighbours at least ~8 m away (so GPS jitter on
     * dense tracks doesn't register as turns), keeping only proper bends
     * (>= [turnMinAngleDeg]) so gentle curves don't trigger a cue.
     */
    private fun detectTurns(): List<TurnInternal> {
        val result = mutableListOf<TurnInternal>()
        val minLeg = 8.0
        for (j in 1 until points.size - 1) {
            val prev = neighbourBefore(j, minLeg) ?: continue
            val next = neighbourAfter(j, minLeg) ?: continue
            val bIn = GeoUtils.bearingDegrees(points[prev], points[j]).toDouble()
            val bOut = GeoUtils.bearingDegrees(points[j], points[next]).toDouble()
            val angle = normalize180(bOut - bIn)
            if (kotlin.math.abs(angle) >= turnMinAngleDeg) {
                val dir = if (angle > 0) TurnDirection.RIGHT else TurnDirection.LEFT
                result += TurnInternal(cumDist[j], dir, angle)
            }
        }
        // Merge turns closer than 20 m, keeping the sharpest.
        val merged = mutableListOf<TurnInternal>()
        for (t in result) {
            val last = merged.lastOrNull()
            if (last != null && t.atDistance - last.atDistance < 20.0) {
                if (kotlin.math.abs(t.angle) > kotlin.math.abs(last.angle)) merged[merged.size - 1] = t
            } else {
                merged += t
            }
        }
        return merged
    }

    private fun neighbourBefore(j: Int, minLeg: Double): Int? {
        var i = j - 1
        while (i >= 0 && cumDist[j] - cumDist[i] < minLeg) i--
        return if (i >= 0) i else if (j - 1 >= 0) j - 1 else null
    }

    private fun neighbourAfter(j: Int, minLeg: Double): Int? {
        var i = j + 1
        while (i < points.size && cumDist[i] - cumDist[j] < minLeg) i++
        return if (i < points.size) i else if (j + 1 < points.size) j + 1 else null
    }

    private fun normalize180(deg: Double): Double {
        var a = (deg + 180.0) % 360.0
        if (a < 0) a += 360.0
        return a - 180.0
    }
}
