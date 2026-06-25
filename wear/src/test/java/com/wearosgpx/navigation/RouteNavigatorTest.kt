package com.wearosgpx.navigation

import com.wearosgpx.data.gpx.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the navigation maths and the thresholds we keep tuning (off-course
 * hysteresis 40/25 m, "proper bend" turn angle 50°, turn look-ahead). All pure
 * geometry — deterministic, no Android, no flakiness. Coordinates are at the
 * equator so 0.001° ≈ 111.32 m in both lat and lon.
 */
class RouteNavigatorTest {

    private val mPerDeg = 111_320.0

    /** Straight route due north along lon = 0. */
    private fun straightRoute() = listOf(
        GeoPoint(0.0, 0.0), GeoPoint(0.001, 0.0), GeoPoint(0.002, 0.0), GeoPoint(0.003, 0.0),
    )

    @Test
    fun onRoute_isNotOffCourse_andTracksProgress() {
        val nav = RouteNavigator(straightRoute())
        val p = nav.update(GeoPoint(0.0015, 0.0), previousOffCourse = false)
        assertFalse(p.isOffCourse)
        assertTrue("crossTrack should be ~0 on the line", p.crossTrackMeters < 2.0)
        assertEquals(0.0015 * mPerDeg, p.distanceAlongMeters, 5.0)
    }

    @Test
    fun offCourse_usesHysteresis_40enter_25exit() {
        val nav = RouteNavigator(straightRoute())
        fun east(meters: Double) = GeoPoint(0.0015, meters / mPerDeg)

        // 50 m off the line → enters off-course (> 40 m enter threshold).
        assertTrue(nav.update(east(50.0), previousOffCourse = false).isOffCourse)
        // 30 m off, previously ON course → does NOT trip (< 40 m enter).
        assertFalse(nav.update(east(30.0), previousOffCourse = false).isOffCourse)
        // 30 m off, previously OFF course → stays off (> 25 m exit). This is the hysteresis.
        assertTrue(nav.update(east(30.0), previousOffCourse = true).isOffCourse)
    }

    @Test
    fun sharpBend_producesTurn_withCorrectDirection() {
        // North 222 m, then a 90° right turn east.
        val route = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.002, 0.0), GeoPoint(0.002, 0.002))
        val nav = RouteNavigator(route)
        val p = nav.update(GeoPoint(0.0015, 0.0), previousOffCourse = false)
        assertNotNull("a 90° bend within look-ahead should be a turn", p.nextTurn)
        assertEquals(TurnDirection.RIGHT, p.nextTurn!!.direction)
        assertTrue("turn should be ahead (~56 m)", p.nextTurn!!.distanceMeters in 30.0..90.0)
    }

    @Test
    fun gentleBend_belowThreshold_producesNoTurn() {
        // ~25° kink — below the 50° "proper bend" threshold, so NOT a turn cue.
        val route = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.002, 0.0), GeoPoint(0.004, 0.000933))
        val nav = RouteNavigator(route)
        val p = nav.update(GeoPoint(0.0015, 0.0), previousOffCourse = false)
        assertNull("a gentle curve must not raise a turn cue", p.nextTurn)
    }

    @Test
    fun remainingAndFraction_areConsistent() {
        val nav = RouteNavigator(straightRoute())
        val total = nav.totalDistanceMeters
        val p = nav.update(GeoPoint(0.0015, 0.0), previousOffCourse = false)
        assertEquals(total - 0.0015 * mPerDeg, p.distanceRemainingMeters, 5.0)
        assertEquals((0.0015 * mPerDeg / total).toFloat(), p.fractionComplete, 0.02f)
    }

    @Test
    fun distanceToStart_isStraightLineToFirstPoint() {
        val nav = RouteNavigator(straightRoute())
        val p = nav.update(GeoPoint(0.001, 0.0), previousOffCourse = false)
        assertEquals(0.001 * mPerDeg, p.distanceToStartMeters, 5.0)
    }
}
