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
 * Snaps a list of waypoints to a road/path-following route via OpenRouteService
 * (foot-walking profile). Input/output points are (lat, lon). Returns null if no
 * key is set or the request fails, so the caller can fall back to straight lines.
 */
object RoutingService {

    private const val ENDPOINT = "https://api.openrouteservice.org/v2/directions/foot-walking/geojson"
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun route(waypoints: List<Pair<Double, Double>>, apiKey: String): List<Pair<Double, Double>>? =
        withContext(Dispatchers.IO) {
            if (waypoints.size < 2 || apiKey.isBlank()) return@withContext null
            // ORS expects [lon, lat] pairs.
            val coords = waypoints.joinToString(",") { "[${it.second},${it.first}]" }
            // "fastest" biases toward direct, main, accessible ways instead of the
            // default "recommended" weighting that favours quiet/green paths (which
            // produced windy detours); avoid steps/fords that force awkward detours.
            val payload = """{"coordinates":[$coords],""" +
                """"preference":"fastest",""" +
                """"options":{"avoid_features":["steps","fords"]},""" +
                """"instructions":false}"""

            runCatching {
                val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Authorization", apiKey)
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/geo+json")
                    doOutput = true
                    connectTimeout = 15_000
                    readTimeout = 15_000
                }
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                json.parseToJsonElement(body).jsonObject["features"]
                    ?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("geometry")?.jsonObject
                    ?.get("coordinates")?.jsonArray
                    ?.map { pair ->
                        val a = pair.jsonArray
                        a[1].jsonPrimitive.double to a[0].jsonPrimitive.double  // -> (lat, lon)
                    }
            }.getOrNull()
        }
}
