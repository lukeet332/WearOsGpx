package com.wearosgpx.mobile.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure parts of the basemap builder: OSM feature classification + line simplification. */
class BaseMapServiceTest {

    @Test
    fun classify_mapsOsmTagsToFeatureTypes() {
        assertEquals(0, BaseMapService.classify("primary", null, null))        // major
        assertEquals(0, BaseMapService.classify("motorway_link", null, null))  // major (link)
        assertEquals(1, BaseMapService.classify("residential", null, null))    // minor
        assertEquals(2, BaseMapService.classify("footway", null, null))        // path
        assertEquals(3, BaseMapService.classify(null, "river", null))          // water (line)
        assertEquals(3, BaseMapService.classify(null, null, "water"))          // water (area)
        assertNull(BaseMapService.classify(null, null, null))                  // nothing
    }

    @Test
    fun simplify_dropsCollinearPoints() {
        val line = listOf(0.0 to 0.0, 1.0 to 1.0, 2.0 to 2.0, 3.0 to 3.0)   // perfectly straight
        val out = BaseMapService.simplify(line, eps = 0.01)
        assertEquals(listOf(0.0 to 0.0, 3.0 to 3.0), out)                   // only endpoints survive
    }

    @Test
    fun simplify_keepsSignificantDeviation() {
        val line = listOf(0.0 to 0.0, 1.0 to 0.5, 2.0 to 0.0)              // clear bump at the middle
        val out = BaseMapService.simplify(line, eps = 0.1)
        assertTrue("the deviating mid-point must be kept", out.contains(1.0 to 0.5))
        assertEquals(3, out.size)
    }

    @Test
    fun simplify_shortLinesUntouched() {
        val line = listOf(0.0 to 0.0, 1.0 to 1.0)
        assertEquals(line, BaseMapService.simplify(line, eps = 0.5))
    }
}
