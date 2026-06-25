package com.wearosgpx.mobile.strava

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure parsing of the Strava read responses (routes / activities / latlng stream). */
class StravaParseTest {

    @Test
    fun routes_parsesIdNameDistance() {
        val r = StravaParse.routes(
            """[{"id":111,"name":"Riverside loop","distance":5012.4},
                {"id":222,"name":"","distance":10000}]"""
        )
        assertEquals(2, r.size)
        assertEquals(111L, r[0].id)
        assertEquals("Riverside loop", r[0].name)
        assertEquals(5012.4, r[0].distanceMeters, 0.01)
        assertEquals("Route", r[1].name)   // blank name falls back
    }

    @Test
    fun activities_keepOnlyGps_andSubtitleHasTypeAndDate() {
        val a = StravaParse.activities(
            """[{"id":1,"name":"Bushy parkrun","distance":5000,"type":"Run",
                 "start_date_local":"2024-09-15T08:30:00Z","map":{"summary_polyline":"abc"}},
                {"id":2,"name":"Treadmill","distance":5000,"type":"Run","map":{"summary_polyline":""}}]"""
        )
        assertEquals(1, a.size)                       // the no-GPS treadmill run is dropped
        assertEquals(1L, a[0].id)
        assertEquals("Run · 2024-09-15", a[0].detail)
    }

    @Test
    fun latLng_parsesKeyByTypeStream() {
        val pts = StravaParse.latLng(
            """{"latlng":{"data":[[51.5,-0.1],[51.51,-0.12]],"type":"latlng"}}"""
        )
        assertEquals(2, pts.size)
        assertEquals(51.51, pts[1].first, 1e-9)
        assertEquals(-0.12, pts[1].second, 1e-9)
    }

    @Test
    fun parsers_returnEmptyOnGarbage() {
        assertTrue(StravaParse.routes("not json").isEmpty())
        assertTrue(StravaParse.activities("{}").isEmpty())
        assertTrue(StravaParse.latLng("[]").isEmpty())
    }
}
