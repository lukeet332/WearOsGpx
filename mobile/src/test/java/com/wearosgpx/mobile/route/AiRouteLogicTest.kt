package com.wearosgpx.mobile.route

import com.wearosgpx.mobile.route.AiRouteLogic.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure logic behind AI route generation: parsing the model's JSON action protocol and
 * the geometry math used for the preview (distance / ascent). No network, no Android.
 */
class AiRouteLogicTest {

    @Test
    fun parseAction_reply() {
        val a = AiRouteLogic.parseAction("""{"action":"reply","reply":"Which park do you mean?"}""")
        assertEquals(Kind.REPLY, a.kind)
        assertEquals("Which park do you mean?", a.reply)
    }

    @Test
    fun parseAction_tool_withArgs() {
        val a = AiRouteLogic.parseAction(
            """{"action":"tool","tool":"round_trip","args":{"lat":51.5,"lon":-0.12,"distance_m":5000}}"""
        )
        assertEquals(Kind.TOOL, a.kind)
        assertEquals("round_trip", a.tool)
        assertEquals(5000.0, AiRouteLogic.argDouble(a, "distance_m"), 0.0001)
        assertEquals(51.5, AiRouteLogic.argDouble(a, "lat"), 0.0001)
    }

    @Test
    fun parseAction_final() {
        val a = AiRouteLogic.parseAction("""{"action":"final","name":"Riverside 5k","reply":"Here you go!"}""")
        assertEquals(Kind.FINAL, a.kind)
        assertEquals("Riverside 5k", a.name)
        assertNull(a.replace)   // a fresh route, not an update
    }

    @Test
    fun parseAction_final_withReplaceIsAnUpdate() {
        val a = AiRouteLogic.parseAction(
            """{"action":"final","name":"Riverside 10k","replace":"riverside_5k.gpx"}"""
        )
        assertEquals(Kind.FINAL, a.kind)
        assertEquals("riverside_5k.gpx", a.replace)
    }

    @Test
    fun argStringList_readsAnArrayArg() {
        val a = AiRouteLogic.parseAction(
            """{"action":"tool","tool":"round_trip","args":{"avoid":["steps","fords"]}}"""
        )
        assertEquals(listOf("steps", "fords"), AiRouteLogic.argStringList(a, "avoid"))
        // missing arg -> empty, never null
        assertTrue(AiRouteLogic.argStringList(a, "nope").isEmpty())
    }

    @Test
    fun parseAction_plainTextIsTreatedAsReply() {
        val a = AiRouteLogic.parseAction("Sure — roughly how far do you want to run?")
        assertEquals(Kind.REPLY, a.kind)
        assertEquals("Sure — roughly how far do you want to run?", a.reply)
    }

    @Test
    fun parseAction_unwrapsCodeFences() {
        val a = AiRouteLogic.parseAction("```json\n{\"action\":\"reply\",\"reply\":\"hi\"}\n```")
        assertEquals(Kind.REPLY, a.kind)
        assertEquals("hi", a.reply)
    }

    @Test
    fun pathDistance_isHaversineSum() {
        // ~1 deg of latitude ≈ 111 km; two ~0.001-deg steps ≈ 222 m total.
        val pts = listOf(51.5000 to -0.1000, 51.5010 to -0.1000, 51.5020 to -0.1000)
        val d = AiRouteLogic.pathDistanceMeters(pts)
        assertTrue("expected ~222m, got $d", d in 200.0..245.0)
    }

    @Test
    fun ascent_sumsPositiveDeltasOnly() {
        // 10 -> 15 (+5) -> 12 (0) -> 20 (+8) = 13
        assertEquals(13.0, AiRouteLogic.ascentMeters(listOf(10.0, 15.0, 12.0, 20.0)), 0.0001)
        // nulls are skipped, not treated as 0
        assertEquals(5.0, AiRouteLogic.ascentMeters(listOf(10.0, null, 15.0)), 0.0001)
    }
}
