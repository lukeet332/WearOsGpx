package com.wearosgpx.data.gpx

import org.junit.Assert.assertEquals
import org.junit.Test

/** Route-model derived metrics (distance + positive ascent). */
class GpxRouteTest {

    @Test
    fun totalDistance_sumsSegments() {
        // Three 0.001° steps north at the equator ≈ 3 × 111.32 m.
        val route = GpxRoute(
            name = "r",
            points = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.001, 0.0), GeoPoint(0.002, 0.0), GeoPoint(0.003, 0.0)),
        )
        assertEquals(3 * 111.32, route.totalDistanceMeters, 2.0)
    }

    @Test
    fun totalAscent_countsOnlyClimbs_ignoresMissingEle() {
        val route = GpxRoute(
            name = "r",
            points = listOf(
                GeoPoint(0.0, 0.0, elevationMeters = 100.0),
                GeoPoint(0.001, 0.0, elevationMeters = 130.0),  // +30
                GeoPoint(0.002, 0.0, elevationMeters = null),   // ignored
                GeoPoint(0.003, 0.0, elevationMeters = 120.0),  // descent (vs 130) → no gain
                GeoPoint(0.004, 0.0, elevationMeters = 145.0),  // +25
            ),
        )
        assertEquals(55.0, route.totalAscentMeters, 0.001)
    }

    @Test
    fun emptyOrSinglePoint_isZeroDistance() {
        assertEquals(0.0, GpxRoute("e", emptyList()).totalDistanceMeters, 0.0)
        assertEquals(0.0, GpxRoute("s", listOf(GeoPoint(1.0, 1.0))).totalDistanceMeters, 0.0)
    }
}
