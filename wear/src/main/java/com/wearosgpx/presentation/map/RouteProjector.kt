package com.wearosgpx.presentation.map

import androidx.compose.ui.geometry.Offset
import com.wearosgpx.data.gpx.GeoPoint
import kotlin.math.cos
import kotlin.math.min

/**
 * Projects geographic points into canvas pixel space for the breadcrumb map.
 *
 * Uses a simple equirectangular projection with longitude scaled by cos(latitude)
 * so the route isn't horizontally stretched (a degree of longitude is shorter than
 * a degree of latitude away from the equator). The whole route is scaled uniformly
 * to fit the canvas with padding, and centered. Y is flipped because screen y grows
 * downward while latitude grows upward (north = up).
 *
 * Pure function — no Android/Compose state — so it's trivially unit-testable and is
 * also reused by the elevation screen.
 */
object RouteProjector {

    fun project(
        points: List<GeoPoint>,
        width: Float,
        height: Float,
        paddingPx: Float,
    ): List<Offset> {
        if (points.isEmpty() || width <= 0f || height <= 0f) return emptyList()

        val centerLatRad = Math.toRadians(points.map { it.latitude }.average())
        val lonScale = cos(centerLatRad).toFloat()

        val xs = FloatArray(points.size) { (points[it].longitude * lonScale).toFloat() }
        val ys = FloatArray(points.size) { points[it].latitude.toFloat() }

        val minX = xs.min(); val maxX = xs.max()
        val minY = ys.min(); val maxY = ys.max()
        val spanX = (maxX - minX).coerceAtLeast(1e-9f)
        val spanY = (maxY - minY).coerceAtLeast(1e-9f)

        val availW = (width - 2 * paddingPx).coerceAtLeast(1f)
        val availH = (height - 2 * paddingPx).coerceAtLeast(1f)
        val scale = min(availW / spanX, availH / spanY)

        // Center the drawn route within the available area.
        val offsetX = paddingPx + (availW - spanX * scale) / 2f
        val offsetY = paddingPx + (availH - spanY * scale) / 2f

        return List(points.size) { i ->
            Offset(
                x = offsetX + (xs[i] - minX) * scale,
                y = offsetY + (maxY - ys[i]) * scale,   // flip Y
            )
        }
    }

    /**
     * Follow-mode projection: a fixed zoom (no fit-to-bounds) with [center]
     * pinned to the middle of the canvas. Points are placed by their real
     * metric offset (north/east) from [center], so the scale is constant
     * regardless of route extent. Output is north-up; the caller rotates the
     * canvas by -heading to get track-up.
     */
    fun projectCentered(
        points: List<GeoPoint>,
        center: GeoPoint,
        width: Float,
        height: Float,
        metersPerPixel: Float,
    ): List<Offset> {
        if (points.isEmpty() || metersPerPixel <= 0f) return emptyList()

        val metersPerDegLat = 111_320.0
        val metersPerDegLon = 111_320.0 * cos(Math.toRadians(center.latitude))
        val cx = width / 2f
        val cy = height / 2f

        return List(points.size) { i ->
            val northMeters = (points[i].latitude - center.latitude) * metersPerDegLat
            val eastMeters = (points[i].longitude - center.longitude) * metersPerDegLon
            Offset(
                x = cx + (eastMeters / metersPerPixel).toFloat(),
                y = cy - (northMeters / metersPerPixel).toFloat(),   // flip Y (north = up)
            )
        }
    }
}
