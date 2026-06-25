package com.wearosgpx.presentation.map

import com.wearosgpx.data.gpx.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure projection maths behind the breadcrumb + basemap rendering. */
class RouteProjectorTest {

    private val w = 200f
    private val h = 200f
    private val pad = 10f

    @Test
    fun project_fitsAllPointsWithinPadding() {
        val pts = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.001, 0.001), GeoPoint(0.002, 0.0))
        val out = RouteProjector.project(pts, w, h, pad)
        assertEquals(pts.size, out.size)
        out.forEach {
            assertTrue("x in bounds", it.x >= pad - 0.5f && it.x <= w - pad + 0.5f)
            assertTrue("y in bounds", it.y >= pad - 0.5f && it.y <= h - pad + 0.5f)
        }
    }

    @Test
    fun projectCentered_centerIsMiddle_andNorthIsUp() {
        val center = GeoPoint(51.5, -0.1)
        val north = GeoPoint(51.501, -0.1)
        val out = RouteProjector.projectCentered(listOf(center, north), center, w, h, metersPerPixel = 1f)
        assertEquals(w / 2, out[0].x, 0.5f)
        assertEquals(h / 2, out[0].y, 0.5f)
        assertTrue("north of centre is higher on screen (smaller y)", out[1].y < out[0].y)
        assertEquals("due north keeps the same x", out[0].x, out[1].x, 0.5f)
    }

    @Test
    fun projectWithFitOf_matchesProject_whenFittingToSelf() {
        val pts = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.001, 0.0), GeoPoint(0.0, 0.001))
        val a = RouteProjector.project(pts, w, h, pad)
        val b = RouteProjector.projectWithFitOf(pts, pts, w, h, pad)
        for (i in pts.indices) {
            assertEquals(a[i].x, b[i].x, 0.001f)
            assertEquals(a[i].y, b[i].y, 0.001f)
        }
    }
}
