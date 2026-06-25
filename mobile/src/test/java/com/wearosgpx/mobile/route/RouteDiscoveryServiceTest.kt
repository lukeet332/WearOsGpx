package com.wearosgpx.mobile.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure way-chaining that turns unordered OSM ways into one continuous route line. */
class RouteDiscoveryServiceTest {

    @Test
    fun assemble_chainsAlignedWays() {
        val a = listOf(0.0 to 0.0, 1.0 to 0.0)
        val b = listOf(1.0 to 0.0, 2.0 to 0.0)
        val out = RouteDiscoveryService.assemble(listOf(a, b))
        assertEquals(0.0 to 0.0, out.first())
        assertEquals(2.0 to 0.0, out.last())
    }

    @Test
    fun assemble_flipsWayToConnectEndpoints() {
        val a = listOf(0.0 to 0.0, 1.0 to 0.0)
        val bReversed = listOf(2.0 to 0.0, 1.0 to 0.0)   // same segment, opposite orientation
        val out = RouteDiscoveryService.assemble(listOf(a, bReversed))
        assertEquals(0.0 to 0.0, out.first())
        assertEquals(2.0 to 0.0, out.last())             // flipped so the chain stays continuous
    }

    @Test
    fun assemble_reachesNearestNextWayBeforeFartherOne() {
        val a = listOf(0.0 to 0.0, 1.0 to 0.0)
        val far = listOf(5.0 to 0.0, 6.0 to 0.0)
        val near = listOf(1.0 to 0.0, 2.0 to 0.0)
        val out = RouteDiscoveryService.assemble(listOf(a, far, near))
        // From `a`'s tail (1,0) the nearest continuation is `near`, so (2,0) appears before (5,0).
        assertTrue(out.indexOf(2.0 to 0.0) < out.indexOf(5.0 to 0.0))
    }

    // A ~5 km line near the equator: 45 steps of 0.001° lat ≈ 45 × 111 m ≈ 5000 m.
    private val start = 51.5 to -0.1
    private fun loopOf(steps: Int) = (0..steps).map { (51.5 + it * 0.001) to -0.1 }

    @Test
    fun qualifyLoop_acceptsRightDistanceNearStart() {
        val d = RouteDiscoveryService.qualifyLoop(loopOf(45), start.first, start.second, 5000.0)
        assertTrue("expected ~5000m, got $d", d != null && d in 4500.0..5500.0)
    }

    @Test
    fun qualifyLoop_rejectsWhenFarFromStart() {
        // same shape, but the start point is ~30 km away (0.3° lon)
        assertEquals(null, RouteDiscoveryService.qualifyLoop(loopOf(45), 51.5, -0.4, 5000.0))
    }

    @Test
    fun qualifyLoop_rejectsWrongDistance() {
        // ~1.1 km line vs a 5 km target → outside ±35%
        assertEquals(null, RouteDiscoveryService.qualifyLoop(loopOf(10), start.first, start.second, 5000.0))
    }
}
