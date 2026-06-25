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
 * Road/path-following routing via OpenRouteService (foot-walking). All points are
 * (lat, lon). Returns null if no key is set or the request fails so callers can fall
 * back. [route] snaps a sequence of waypoints; [roundTrip] generates a loop of ~a
 * target length from a single start (used by AI route generation for "5k loop from here").
 */
object RoutingService {

    private const val ENDPOINT = "https://api.openrouteservice.org/v2/directions/foot-walking/geojson"
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Snap [waypoints] to roads/paths. [prefer] = "quiet" biases toward greener/quieter
     * paths (ORS "recommended"), anything else stays direct ("fastest"); [avoid] can drop
     * steps/fords/ferries. Defaults reproduce the old direct + avoid steps/fords behaviour.
     */
    suspend fun route(
        waypoints: List<Pair<Double, Double>>,
        apiKey: String,
        prefer: String? = null,
        avoid: List<String> = listOf("steps", "fords"),
    ): List<Pair<Double, Double>>? =
        withContext(Dispatchers.IO) {
            if (waypoints.size < 2 || apiKey.isBlank()) return@withContext null
            val payload = RoutingPayloads.directions(waypoints, prefer, avoid)
            runCatching { post(payload, apiKey) }.getOrNull()
        }

    /**
     * A round-trip loop of roughly [lengthMeters], starting and ending at [start]. ORS
     * shapes the loop itself; [seed] varies its direction/shape so the agent can offer an
     * alternative if asked; [avoid] can drop steps/fords/ferries. Returns the (lat, lon)
     * geometry, or null on failure.
     */
    suspend fun roundTrip(
        start: Pair<Double, Double>,
        lengthMeters: Double,
        apiKey: String,
        seed: Int = 1,
        avoid: List<String> = emptyList(),
    ): List<Pair<Double, Double>>? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || lengthMeters < 200) return@withContext null
        val payload = RoutingPayloads.roundTrip(start, lengthMeters, seed, avoid)
        runCatching { post(payload, apiKey) }.getOrNull()
    }

    /** POST a directions payload and parse the GeoJSON geometry into (lat, lon) points. */
    private fun post(payload: String, apiKey: String): List<Pair<Double, Double>>? {
        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", apiKey)
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/geo+json")
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 20_000
        }
        conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        return json.parseToJsonElement(body).jsonObject["features"]
            ?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("geometry")?.jsonObject
            ?.get("coordinates")?.jsonArray
            ?.map { pair ->
                val a = pair.jsonArray
                a[1].jsonPrimitive.double to a[0].jsonPrimitive.double  // -> (lat, lon)
            }
    }
}
