package com.wearosgpx.mobile.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure route-shaping: ORS preference mapping, avoid-feature filtering, payload shape. */
class RoutingPayloadsTest {

    @Test
    fun preference_quietMapsToRecommended_elseFastest() {
        assertEquals("recommended", RoutingPayloads.preferenceFor("quiet"))
        assertEquals("recommended", RoutingPayloads.preferenceFor("Scenic"))
        assertEquals("fastest", RoutingPayloads.preferenceFor("direct"))
        assertEquals("fastest", RoutingPayloads.preferenceFor(null))
    }

    @Test
    fun normalizeAvoid_keepsOnlyAllowed_andDropsJunk() {
        assertEquals(listOf("steps", "fords"), RoutingPayloads.normalizeAvoid(listOf("STEPS", "fords", "highways")))
        assertTrue(RoutingPayloads.normalizeAvoid(listOf("nonsense")).isEmpty())
    }

    @Test
    fun directions_includesPreferenceAndAvoid() {
        val p = RoutingPayloads.directions(
            listOf(51.5 to -0.1, 51.6 to -0.2),
            prefer = "quiet",
            avoid = listOf("steps"),
        )
        assertTrue(p, p.contains("\"preference\":\"recommended\""))
        assertTrue(p, p.contains("\"avoid_features\":[\"steps\"]"))
        // ORS wants [lon,lat]
        assertTrue(p, p.contains("[-0.1,51.5]"))
    }

    @Test
    fun directions_noAvoidOmitsOptions() {
        val p = RoutingPayloads.directions(listOf(51.5 to -0.1, 51.6 to -0.2))
        assertFalse(p, p.contains("avoid_features"))
        assertTrue(p, p.contains("\"preference\":\"fastest\""))
    }

    @Test
    fun roundTrip_carriesLengthSeedAndAvoid() {
        val p = RoutingPayloads.roundTrip(51.5 to -0.1, 5000.0, seed = 3, avoid = listOf("fords", "ferries"))
        assertTrue(p, p.contains("\"length\":5000"))
        assertTrue(p, p.contains("\"seed\":3"))
        assertTrue(p, p.contains("\"avoid_features\":[\"fords\",\"ferries\"]"))
    }
}
