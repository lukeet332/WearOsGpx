package com.wearosgpx.mobile.route

import android.content.Context
import android.graphics.ColorMatrixColorFilter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

// Inverts the light MAPNIK tiles to a dark theme matching the app (same as the creator).
private val DARK_MAP_FILTER = ColorMatrixColorFilter(
    floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    ),
)
private const val NEON = 0xFF39FF14.toInt()

/**
 * Read-only route preview on a REAL OpenStreetMap (osmdroid) — the neon route line over
 * dark map tiles, fitted to the route. Used on the phone (which has live map access) by the
 * route-list detail dialog and the AI route confirm dialog. The watch uses the baked vector
 * basemap instead, since it has no live map engine.
 */
@Composable
internal fun RoutePreviewMapView(points: List<Pair<Double, Double>>, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            Configuration.getInstance().apply {
                load(ctx.applicationContext, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
                userAgentValue = ctx.packageName
            }
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(false)
                isClickable = false
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                overlayManager.tilesOverlay.setColorFilter(DARK_MAP_FILTER)
                val geo = points.map { GeoPoint(it.first, it.second) }
                if (geo.size >= 2) {
                    overlays.add(
                        Polyline(this).apply {
                            setPoints(geo)
                            outlinePaint.color = NEON
                            outlinePaint.strokeWidth = 8f
                        }
                    )
                    // Fit to the route once the view has been laid out (so it has a size).
                    post { zoomToBoundingBox(BoundingBox.fromGeoPoints(geo), false, 48) }
                }
            }
        },
        onRelease = { it.onDetach() },
    )
}
