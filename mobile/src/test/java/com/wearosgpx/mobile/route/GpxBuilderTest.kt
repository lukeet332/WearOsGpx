package com.wearosgpx.mobile.route

import org.junit.Assert.assertTrue
import org.junit.Test

/** The route-GPX builder used for created/snapped routes. Pure string output. */
class GpxBuilderTest {

    @Test
    fun build_emitsTrackPointsAndName() {
        val gpx = GpxBuilder.build(
            name = "Test Route",
            points = listOf(51.5 to -0.1, 51.51 to -0.12),
        )
        assertTrue(gpx.contains("<gpx"))
        assertTrue(gpx.contains("<name>Test Route</name>"))
        assertTrue(gpx.contains("lat=\"51.500000\""))
        assertTrue(gpx.contains("lon=\"-0.120000\""))
        assertTrue("two points → two trkpt", gpx.split("<trkpt").size - 1 == 2)
    }

    @Test
    fun build_includesElevationWhenProvided() {
        val gpx = GpxBuilder.build(
            name = "Hill",
            points = listOf(51.5 to -0.1, 51.51 to -0.1),
            elevations = listOf(10.0, 42.5),
        )
        assertTrue(gpx.contains("<ele>42.5</ele>"))
    }

    @Test
    fun build_escapesXmlInName() {
        val gpx = GpxBuilder.build(name = "A & B <loop>", points = listOf(0.0 to 0.0, 1.0 to 1.0))
        assertTrue(gpx.contains("A &amp; B &lt;loop&gt;"))
    }

    @Test
    fun renamed_swapsNameButKeepsGeometry() {
        val gpx = GpxBuilder.build("Old Name", listOf(51.5 to -0.1, 51.51 to -0.12), listOf(10.0, 20.0))
        val out = GpxBuilder.renamed(gpx, "New & Better")
        assertTrue(out.contains("<name>New &amp; Better</name>"))
        assertTrue("old name gone", !out.contains("Old Name"))
        assertTrue("geometry preserved", out.contains("lat=\"51.500000\"") && out.contains("<ele>20.0</ele>"))
    }

    @Test
    fun renamed_noNameTagLeavesDocUnchanged() {
        val doc = "<gpx><trk><trkseg></trkseg></trk></gpx>"
        assertTrue(GpxBuilder.renamed(doc, "X") == doc)
    }
}
