package com.wearosgpx.mobile.route

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure parsing + matching for parkrun's public `events.json` (the GeoJSON feed its own
 * event map uses). Each event is a single Point — name + START location, NOT the course
 * geometry — so this only resolves *which* parkrun and *where* it starts; the actual
 * course still comes from OSM (or a 5k loop from the start). No network, no Android.
 */
object ParkrunParse {

    /** seriesid 1 = adult 5k, 2 = junior 2k. */
    data class Event(
        val name: String,        // short slug, e.g. "bushy"
        val longName: String,    // "Bushy parkrun"
        val location: String,    // "Bushy Park"
        val lat: Double,
        val lon: Double,
        val seriesId: Int,
    ) {
        /** parkruns are a fixed distance by series: adult 5 km, junior 2 km. */
        val distanceMeters: Int get() = if (seriesId == 2) 2000 else 5000
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** Parse the events GeoJSON (features live under "events", or at the root). */
    fun events(body: String): List<Event> = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        val features = (root["events"]?.jsonObject?.get("features") ?: root["features"])
            ?.jsonArray ?: return emptyList()
        features.mapNotNull { el ->
            val o = el.jsonObject
            val coords = o["geometry"]?.jsonObject?.get("coordinates")?.jsonArray ?: return@mapNotNull null
            val lon = coords.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val lat = coords.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val p = o["properties"]?.jsonObject ?: return@mapNotNull null
            val name = p["eventname"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            Event(
                name = name,
                longName = p["EventLongName"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: name,
                location = p["EventLocation"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                lat = lat,
                lon = lon,
                seriesId = p["seriesid"]?.jsonPrimitive?.intOrNull ?: 1,
            )
        }
    }.getOrDefault(emptyList())

    /** Events whose name/long-name/location contains [query], best matches first. */
    fun search(events: List<Event>, query: String): List<Event> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return emptyList()
        return events.filter {
            it.longName.lowercase().contains(q) || it.name.lowercase().contains(q) ||
                it.location.lowercase().contains(q)
        }.sortedWith(
            compareByDescending<Event> { it.longName.lowercase().startsWith(q) }
                .thenBy { it.longName.length },
        )
    }

    /** The [n] events closest to (lat, lon). */
    fun nearest(events: List<Event>, lat: Double, lon: Double, n: Int): List<Event> =
        events.sortedBy { haversine(lat, lon, it.lat, it.lon) }.take(n)

    private fun haversine(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(la2 - la1)
        val dLon = Math.toRadians(lo2 - lo1)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(la1)) * cos(Math.toRadians(la2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(min(1.0, sqrt(h)))
    }
}
