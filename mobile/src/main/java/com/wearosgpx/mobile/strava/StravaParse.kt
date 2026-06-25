package com.wearosgpx.mobile.strava

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Pure parsing of the Strava API responses we read (the user's own routes, recent
 * activities, and an activity's GPS stream). No network, no Android — unit tested.
 * Network + auth live in [StravaClient].
 */
object StravaParse {

    /** A pickable Strava item (route or activity) for the import list. */
    data class Item(
        val id: Long,
        val name: String,
        val distanceMeters: Double,
        val detail: String,   // short subtitle, e.g. "Route" or "Run · 2024-09-15"
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Parse `GET /athletes/{id}/routes` (an array of route summaries). */
    fun routes(body: String): List<Item> = runCatching {
        json.parseToJsonElement(body).jsonArray.mapNotNull { el ->
            val o = el.jsonObject
            val id = o["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val name = o["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "Route"
            val dist = o["distance"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            Item(id, name, dist, "Route")
        }
    }.getOrDefault(emptyList())

    /**
     * Parse `GET /athlete/activities` (an array). Only activities with GPS (a non-blank
     * `map.summary_polyline`) are kept — a treadmill/manual entry has no track to import.
     */
    fun activities(body: String): List<Item> = runCatching {
        json.parseToJsonElement(body).jsonArray.mapNotNull { el ->
            val o = el.jsonObject
            val hasGps = o["map"]?.jsonObject?.get("summary_polyline")
                ?.jsonPrimitive?.contentOrNull?.isNotBlank() == true
            if (!hasGps) return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val name = o["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "Activity"
            val dist = o["distance"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val type = o["type"]?.jsonPrimitive?.contentOrNull ?: "Activity"
            val date = o["start_date_local"]?.jsonPrimitive?.contentOrNull?.take(10).orEmpty()
            Item(id, name, dist, listOf(type, date).filter { it.isNotBlank() }.joinToString(" · "))
        }
    }.getOrDefault(emptyList())

    /**
     * Parse `GET /activities/{id}/streams?keys=latlng&key_by_type=true`
     * → `{"latlng":{"data":[[lat,lon],...]}}` into (lat, lon) points.
     */
    fun latLng(body: String): List<Pair<Double, Double>> = runCatching {
        json.parseToJsonElement(body).jsonObject["latlng"]?.jsonObject
            ?.get("data")?.jsonArray
            ?.mapNotNull { el ->
                val p = el.jsonArray
                val lat = p.getOrNull(0)?.jsonPrimitive?.doubleOrNull
                val lon = p.getOrNull(1)?.jsonPrimitive?.doubleOrNull
                if (lat != null && lon != null) lat to lon else null
            } ?: emptyList()
    }.getOrDefault(emptyList())
}
