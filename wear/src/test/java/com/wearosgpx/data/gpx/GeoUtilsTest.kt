package com.wearosgpx.data.gpx

import org.junit.Assert.assertEquals
import org.junit.Test

/** Foundational geo math used everywhere (distance + bearing). Pure, deterministic. */
class GeoUtilsTest {

    @Test
    fun haversine_samePoint_isZero() {
        val p = GeoPoint(51.5, -0.12)
        assertEquals(0.0, GeoUtils.haversineMeters(p, p), 1e-6)
    }

    @Test
    fun haversine_thousandthDegreeLat_isAbout111m() {
        val d = GeoUtils.haversineMeters(GeoPoint(0.0, 0.0), GeoPoint(0.001, 0.0))
        assertEquals(111.32, d, 1.0)
    }

    @Test
    fun bearing_cardinalDirections() {
        val o = GeoPoint(0.0, 0.0)
        assertEquals(0.0, GeoUtils.bearingDegrees(o, GeoPoint(1.0, 0.0)).toDouble(), 1.0)    // N
        assertEquals(90.0, GeoUtils.bearingDegrees(o, GeoPoint(0.0, 1.0)).toDouble(), 1.0)   // E
        assertEquals(180.0, GeoUtils.bearingDegrees(o, GeoPoint(-1.0, 0.0)).toDouble(), 1.0) // S
        assertEquals(270.0, GeoUtils.bearingDegrees(o, GeoPoint(0.0, -1.0)).toDouble(), 1.0) // W
    }
}
