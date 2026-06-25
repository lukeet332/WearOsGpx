package com.wearosgpx.mobile.route

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.min

private val Neon = Color(0xFF39FF14)

/**
 * Route preview in the watch's neon-on-black style: the surrounding [baseMap] (grey roads,
 * blue water) under the GPX route line, all fitted to the route. Shared by the route list's
 * detail dialog and the AI route generator's confirm dialog. With a null [baseMap] it just
 * draws the route line (e.g. before the basemap has finished building).
 */
@Composable
internal fun RoutePreviewMap(
    points: List<Pair<Double, Double>>,
    baseMap: BaseMap?,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.background(Color.Black)) {
        if (points.size < 2) return@Canvas
        val centerLatRad = Math.toRadians(points.map { it.first }.average())
        val lonScale = cos(centerLatRad).toFloat()
        val xs = points.map { (it.second * lonScale).toFloat() }
        val ys = points.map { it.first.toFloat() }
        val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
        val spanX = (maxX - minX).coerceAtLeast(1e-9f)
        val spanY = (maxY - minY).coerceAtLeast(1e-9f)
        val pad = 14.dp.toPx()
        val availW = (size.width - 2 * pad).coerceAtLeast(1f)
        val availH = (size.height - 2 * pad).coerceAtLeast(1f)
        val scale = min(availW / spanX, availH / spanY)
        val offX = pad + (availW - spanX * scale) / 2f
        val offY = pad + (availH - spanY * scale) / 2f
        fun project(lat: Double, lon: Double): Offset =
            Offset(offX + ((lon * lonScale).toFloat() - minX) * scale, offY + (maxY - lat.toFloat()) * scale)

        // Basemap under the route (fitted to the route, so off-route bits clip to edges).
        baseMap?.features?.forEach { f ->
            val fpts = ArrayList<Offset>(f.c.size / 2)
            var i = 0
            while (i + 1 < f.c.size) { fpts.add(project(f.c[i], f.c[i + 1])); i += 2 }
            if (fpts.size >= 2) {
                drawPath(offsetsPath(fpts), baseMapColor(f.t), style = Stroke(baseMapWidthDp(f.t).dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }

        val pts = points.map { project(it.first, it.second) }
        val path = offsetsPath(pts)
        drawPath(path, Neon.copy(alpha = 0.15f), style = Stroke(10.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, Neon, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(Neon, radius = 4.dp.toPx(), center = pts.first())                 // start
        drawCircle(Color.White, radius = 4.dp.toPx(), center = pts.last())           // end
        drawCircle(Neon, radius = 4.dp.toPx(), center = pts.last(), style = Stroke(1.5.dp.toPx()))
    }
}

private fun baseMapColor(t: Int): Color = when (t) {
    0 -> Color(0xFF666666); 1 -> Color(0xFF474747); 2 -> Color(0xFF3A3A3A); 3 -> Color(0xFF2C4A63)
    else -> Color(0xFF3A3A3A)
}

private fun baseMapWidthDp(t: Int): Float = when (t) { 0 -> 1.3f; 1 -> 0.9f; 2 -> 0.7f; 3 -> 1.0f; else -> 0.9f }

private fun offsetsPath(pts: List<Offset>): Path = Path().apply {
    if (pts.isEmpty()) return@apply
    moveTo(pts[0].x, pts[0].y)
    for (i in 1..pts.lastIndex) lineTo(pts[i].x, pts[i].y)
}
