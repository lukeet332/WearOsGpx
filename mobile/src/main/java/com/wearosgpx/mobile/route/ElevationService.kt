package com.wearosgpx.mobile.route

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * Looks up terrain elevation for plotted points via the Open-Meteo Elevation API
 * (free, no key). Returns one value per input point, aligned by index; entries are
 * null if a batch fails, so route saving still works offline (just without ascent).
 */
object ElevationService {

    private const val MAX_PER_REQUEST = 100
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun lookup(points: List<Pair<Double, Double>>): List<Double?> =
        withContext(Dispatchers.IO) {
            val out = ArrayList<Double?>(points.size)
            for (chunk in points.chunked(MAX_PER_REQUEST)) {
                val lats = chunk.joinToString(",") { "%.6f".format(it.first) }
                val lons = chunk.joinToString(",") { "%.6f".format(it.second) }
                val url = "https://api.open-meteo.com/v1/elevation?latitude=$lats&longitude=$lons"
                val elevations = runCatching {
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 10_000
                        readTimeout = 10_000
                    }
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    json.parseToJsonElement(body).jsonObject["elevation"]
                        ?.jsonArray?.map { it.jsonPrimitive.double }
                }.getOrNull()

                if (elevations != null && elevations.size == chunk.size) {
                    out.addAll(elevations)
                } else {
                    repeat(chunk.size) { out.add(null) }
                }
            }
            out
        }
}
