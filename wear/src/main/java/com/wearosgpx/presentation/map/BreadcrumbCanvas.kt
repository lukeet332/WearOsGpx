package com.wearosgpx.presentation.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Text
import com.wearosgpx.data.gpx.BaseMap
import com.wearosgpx.data.gpx.GeoPoint
import com.wearosgpx.data.gpx.MapFeature
import kotlin.math.min

/** Overview = whole route fitted, north-up. Follow = zoomed, centered on you, track-up. */
enum class MapMode { OVERVIEW, FOLLOW }

/**
 * Canvas breadcrumb map.
 *
 *  - [route]   the planned GPX line, drawn dim in the background.
 *  - [track]   the path run so far, drawn as a bright neon line on top.
 *  - [current] live position. Required for [MapMode.FOLLOW].
 *  - [headingDegrees] heading (clockwise from north) for track-up rotation.
 *  - [viewRadiusMeters] how much ground the follow view shows from center to edge.
 */
@Composable
fun BreadcrumbCanvas(
    route: List<GeoPoint>,
    modifier: Modifier = Modifier,
    track: List<GeoPoint> = emptyList(),
    current: GeoPoint? = null,
    headingDegrees: Float? = null,
    mode: MapMode = MapMode.OVERVIEW,
    viewRadiusMeters: Float = 150f,
    routeColor: Color = Color(0xFF3A3A3A),
    neon: Color = Color(0xFF39FF14),
    ambient: Boolean = false,
    baseMap: BaseMap? = null,
) {
    if (route.isEmpty() && track.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No route loaded") }
        return
    }

    // Ambient (always-on): no glow, thin dim lines, outline chevron — burn-in safe.
    val glow = !ambient
    val drawRoute = if (ambient) Color(0xFF2A2A2A) else routeColor
    val drawNeon = if (ambient) Color(0xFF6E9E6E) else neon
    // Quiet grey/blue basemap recedes so the neon route stays the hero. Skipped in
    // ambient to keep the low-power render minimal.
    val bm = if (ambient) null else baseMap

    Canvas(modifier.fillMaxSize()) {
        val pad = 16.dp.toPx()
        if (mode == MapMode.FOLLOW && current != null) {
            drawFollow(route, track, current, headingDegrees ?: 0f, viewRadiusMeters, pad, drawRoute, drawNeon, glow, bm)
        } else {
            drawOverview(route, track, current, pad, drawRoute, drawNeon, glow, bm)
        }
    }
}

private fun baseColor(t: Int): Color = when (t) {
    0 -> Color(0xFF666666)   // major road
    1 -> Color(0xFF474747)   // minor road
    2 -> Color(0xFF3A3A3A)   // path
    3 -> Color(0xFF2C4A63)   // water (muted blue)
    else -> Color(0xFF3A3A3A)
}

private fun baseWidthDp(t: Int): Float = when (t) {
    0 -> 1.6f; 1 -> 1.1f; 2 -> 0.9f; 3 -> 1.3f; else -> 1.0f
}

private fun MapFeature.toGeoPoints(): List<GeoPoint> = buildList {
    var i = 0
    while (i + 1 < c.size) { add(GeoPoint(c[i], c[i + 1])); i += 2 }
}

/** Draw the baked basemap features (roads/paths/water) using [project] for the current view. */
private fun DrawScope.drawBaseMap(baseMap: BaseMap?, project: (List<GeoPoint>) -> List<Offset>) {
    val features = baseMap?.features ?: return
    for (f in features) {
        val pts = project(f.toGeoPoints())
        if (pts.size < 2) continue
        drawPath(
            pathOf(pts),
            baseColor(f.t),
            style = Stroke(baseWidthDp(f.t).dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/** Whole route fitted to the screen, north-up. */
private fun DrawScope.drawOverview(
    route: List<GeoPoint>,
    track: List<GeoPoint>,
    current: GeoPoint?,
    pad: Float,
    routeColor: Color,
    neon: Color,
    glow: Boolean,
    baseMap: BaseMap?,
) {
    val combined = buildList {
        addAll(route); addAll(track); if (current != null) add(current)
    }
    val projected = RouteProjector.project(combined, size.width, size.height, pad)

    var idx = 0
    val routePts = projected.subList(idx, idx + route.size).also { idx += route.size }
    val trackPts = projected.subList(idx, idx + track.size).also { idx += track.size }
    val currentPt = if (current != null) projected[idx] else null

    // Basemap first (under the route), fitted with the route's transform.
    drawBaseMap(baseMap) { RouteProjector.projectWithFitOf(it, combined, size.width, size.height, pad) }
    drawRouteAndTrack(routePts, trackPts, routeColor, neon, glow)

    // End-of-route (destination) marker.
    routePts.lastOrNull()?.let { end ->
        drawCircle(routeColor, radius = 5.dp.toPx(), center = end)
        drawCircle(Color.White, radius = 5.dp.toPx(), center = end, style = Stroke(1.5.dp.toPx()))
    }
    // Live position dot.
    currentPt?.let { pos ->
        drawCircle(neon.copy(alpha = 0.35f), radius = 9.dp.toPx(), center = pos)
        drawCircle(Color.White, radius = 4.dp.toPx(), center = pos)
        drawCircle(neon, radius = 4.dp.toPx(), center = pos, style = Stroke(2.dp.toPx()))
    }
}

/** Zoomed, centered on [current], rotated so heading points up (track-up). */
private fun DrawScope.drawFollow(
    route: List<GeoPoint>,
    track: List<GeoPoint>,
    current: GeoPoint,
    heading: Float,
    viewRadiusMeters: Float,
    pad: Float,
    routeColor: Color,
    neon: Color,
    glow: Boolean,
    baseMap: BaseMap?,
) {
    val radiusPx = (min(size.width, size.height) / 2f - pad).coerceAtLeast(1f)
    val metersPerPixel = viewRadiusMeters / radiusPx
    val centerPx = Offset(size.width / 2f, size.height / 2f)

    val routePts = RouteProjector.projectCentered(route, current, size.width, size.height, metersPerPixel)
    val trackPts = RouteProjector.projectCentered(track, current, size.width, size.height, metersPerPixel)

    // Rotate the world by -heading around screen center → the route ahead points up.
    rotate(degrees = -heading, pivot = centerPx) {
        drawBaseMap(baseMap) { RouteProjector.projectCentered(it, current, size.width, size.height, metersPerPixel) }
        drawRouteAndTrack(routePts, trackPts, routeColor, neon, glow)
        routePts.lastOrNull()?.let { end ->
            drawCircle(routeColor, radius = 5.dp.toPx(), center = end)
            drawCircle(Color.White, radius = 5.dp.toPx(), center = end, style = Stroke(1.5.dp.toPx()))
        }
    }

    // "You" chevron — fixed at center, always pointing up (your heading).
    if (glow) drawCircle(neon.copy(alpha = 0.30f), radius = 11.dp.toPx(), center = centerPx)
    val c = centerPx
    val s = 7.dp.toPx()
    val chevron = Path().apply {
        moveTo(c.x, c.y - s)             // nose (up)
        lineTo(c.x - s * 0.8f, c.y + s)  // bottom-left
        lineTo(c.x, c.y + s * 0.4f)      // notch
        lineTo(c.x + s * 0.8f, c.y + s)  // bottom-right
        close()
    }
    if (glow) {
        drawPath(chevron, Color.White)
        drawPath(chevron, neon, style = Stroke(1.5.dp.toPx(), join = StrokeJoin.Round))
    } else {
        // Ambient: outline only, no fill — minimal lit pixels.
        drawPath(chevron, neon, style = Stroke(1.5.dp.toPx(), join = StrokeJoin.Round))
    }
}

/** Shared: dim planned route + neon glow track + start dot. */
private fun DrawScope.drawRouteAndTrack(
    routePts: List<Offset>,
    trackPts: List<Offset>,
    routeColor: Color,
    neon: Color,
    glow: Boolean,
) {
    if (routePts.size >= 2) {
        val w = if (glow) 2.dp.toPx() else 1.dp.toPx()
        drawPath(pathOf(routePts), routeColor, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    if (trackPts.size >= 2) {
        val p = pathOf(trackPts)
        if (glow) {
            drawPath(p, neon.copy(alpha = 0.12f), style = Stroke(12.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(p, neon.copy(alpha = 0.30f), style = Stroke(6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(p, neon, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        } else {
            // Ambient: a single thin stroke, no glow halo.
            drawPath(p, neon, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
    if (glow) {
        (trackPts.firstOrNull() ?: routePts.firstOrNull())?.let { start ->
            drawCircle(neon, radius = 4.dp.toPx(), center = start)
        }
    }
}

private fun pathOf(points: List<Offset>): Path = Path().apply {
    if (points.isEmpty()) return@apply
    moveTo(points[0].x, points[0].y)
    for (i in 1..points.lastIndex) lineTo(points[i].x, points[i].y)
}
