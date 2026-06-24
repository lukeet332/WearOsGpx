package com.wearosgpx.mobile.route

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Place search via OpenStreetMap Nominatim (free, no key). Used to jump the map to
 * a start location. Nominatim requires a descriptive User-Agent and ~1 req/sec.
 */
object GeocodingService {

    data class Place(val lat: Double, val lon: Double, val label: String)

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(query: String): Place? = searchMany(query, limit = 1).firstOrNull()

    /** Up to [limit] matching places, for type-ahead suggestions. */
    suspend fun searchMany(query: String, limit: Int = 5): List<Place> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val url = "https://nominatim.openstreetmap.org/search?format=json&limit=$limit&q=" +
            URLEncoder.encode(query, "UTF-8")
        runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", "WearOsGpx/1.0 (route planner)")
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            json.parseToJsonElement(body).jsonArray.mapNotNull { el ->
                val o = el.jsonObject
                val lat = o["lat"]?.jsonPrimitive?.content?.toDoubleOrNull()
                val lon = o["lon"]?.jsonPrimitive?.content?.toDoubleOrNull()
                val label = o["display_name"]?.jsonPrimitive?.content
                if (lat != null && lon != null && label != null) Place(lat, lon, label) else null
            }
        }.getOrDefault(emptyList())
    }
}
