package com.wearosgpx.mobile.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure parsing/matching of parkrun's events.json (locations only — not courses). */
class ParkrunParseTest {

    // features nested under "events", coordinates are [lon, lat]
    private val sample = """
      {"events":{"type":"FeatureCollection","features":[
        {"id":280,"type":"Feature","geometry":{"type":"Point","coordinates":[-0.335,51.410]},
         "properties":{"eventname":"bushy","EventLongName":"Bushy parkrun","EventLocation":"Bushy Park","seriesid":1}},
        {"id":1,"type":"Feature","geometry":{"type":"Point","coordinates":[-1.310,51.069]},
         "properties":{"eventname":"winchester","EventLongName":"Winchester parkrun","EventLocation":"North Walls","seriesid":1}},
        {"id":99,"type":"Feature","geometry":{"type":"Point","coordinates":[-0.34,51.41]},
         "properties":{"eventname":"bushy-juniors","EventLongName":"Bushy junior parkrun","EventLocation":"Bushy Park","seriesid":2}}
      ]}}
    """.trimIndent()

    @Test
    fun events_parsesPointFeatures_withLonLatOrder() {
        val e = ParkrunParse.events(sample)
        assertEquals(3, e.size)
        val bushy = e.first { it.name == "bushy" }
        assertEquals("Bushy parkrun", bushy.longName)
        assertEquals(51.410, bushy.lat, 1e-6)   // lat is coords[1]
        assertEquals(-0.335, bushy.lon, 1e-6)   // lon is coords[0]
        assertEquals(5000, bushy.distanceMeters)
    }

    @Test
    fun juniorEvent_is2k() {
        val junior = ParkrunParse.events(sample).first { it.seriesId == 2 }
        assertEquals(2000, junior.distanceMeters)
    }

    @Test
    fun search_matchesByName_bestFirst() {
        val r = ParkrunParse.search(ParkrunParse.events(sample), "bushy")
        assertEquals(2, r.size)
        assertEquals("Bushy parkrun", r[0].longName)   // starts-with beats the junior one
    }

    @Test
    fun nearest_ordersByDistance() {
        val r = ParkrunParse.nearest(ParkrunParse.events(sample), 51.07, -1.31, 1)
        assertEquals("winchester", r[0].name)
    }

    @Test
    fun events_emptyOnGarbage() {
        assertTrue(ParkrunParse.events("nope").isEmpty())
        assertTrue(ParkrunParse.events("{}").isEmpty())
    }
}
