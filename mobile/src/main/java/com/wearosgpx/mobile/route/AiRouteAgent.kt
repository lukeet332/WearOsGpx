package com.wearosgpx.mobile.route

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Conversational route-planning agent. The LLM (any OpenAI-compatible provider) drives a
 * tool-calling loop over our REAL APIs — it never invents coordinates: it asks for the
 * current location, geocodes places, and gets actual road geometry from OpenRouteService.
 * It can also ask the user clarifying questions, and when ready it finalises the last
 * routed geometry for preview + save. Pure parsing/geometry lives in [AiRouteLogic].
 */
class AiRouteAgent(
    private val apiKey: String,
    private val model: String,
    private val baseUrl: String,
    private val orsKey: String,
    private val currentLocation: Pair<Double, Double>?,
) {
    data class RoutedGeometry(
        val points: List<Pair<Double, Double>>,
        val elevations: List<Double?>,
        val distanceM: Double,
        val ascentM: Double,
    )

    sealed interface Turn {
        data class Reply(val text: String) : Turn
        data class RouteReady(val name: String, val geometry: RoutedGeometry) : Turn
        data class Failed(val text: String) : Turn
    }

    private data class Msg(val role: String, val content: String)

    private val json = Json { ignoreUnknownKeys = true }
    private val messages = mutableListOf(Msg("system", SYSTEM_PROMPT))
    private var lastRoute: RoutedGeometry? = null

    /** Send the user's message and run the agent until it replies or has a route ready. */
    suspend fun send(userText: String): Turn {
        messages.add(Msg("user", userText))
        repeat(MAX_STEPS) {
            val raw = callLlm()
                ?: return Turn.Failed("No response from the AI — check the API key, model and base URL in Settings.")
            messages.add(Msg("assistant", raw))
            val action = AiRouteLogic.parseAction(raw)
            when (action.kind) {
                AiRouteLogic.Kind.REPLY -> return Turn.Reply(action.reply ?: "…")
                AiRouteLogic.Kind.FINAL -> {
                    val g = lastRoute
                    if (g == null) {
                        messages.add(Msg("user", "TOOL_RESULT error: no route computed yet — call round_trip or route first."))
                        return@repeat
                    }
                    return Turn.RouteReady(action.name?.takeIf { it.isNotBlank() } ?: "AI route", g)
                }
                AiRouteLogic.Kind.TOOL -> {
                    val result = runTool(action)
                    messages.add(Msg("user", "TOOL_RESULT ${action.tool}: $result"))
                    // loop again without waiting for the user
                }
            }
        }
        return Turn.Failed("I couldn't work that out — try giving a start place and a distance, e.g. \"5k loop from here\".")
    }

    private suspend fun runTool(a: AiRouteLogic.Action): String = when (a.tool) {
        "current_location" ->
            currentLocation?.let { """{"lat":${it.first},"lon":${it.second}}""" }
                ?: """{"error":"location unavailable — ask the user for a start place"}"""

        "geocode" -> {
            val q = AiRouteLogic.argString(a, "query")
            if (q.isNullOrBlank()) """{"error":"missing query"}"""
            else GeocodingService.search(q)?.let {
                """{"lat":${it.lat},"lon":${it.lon},"label":${JsonPrimitive(it.label)}}"""
            } ?: """{"error":"no match for '$q'"}"""
        }

        "round_trip" -> {
            val lat = AiRouteLogic.argDouble(a, "lat")
            val lon = AiRouteLogic.argDouble(a, "lon")
            val dist = AiRouteLogic.argDouble(a, "distance_m")
            if (lat.isNaN() || lon.isNaN() || dist.isNaN()) """{"error":"need lat, lon and distance_m"}"""
            else {
                val seed = AiRouteLogic.argDouble(a, "seed").let { if (it.isNaN()) 1 else it.toInt() }
                val pts = RoutingService.roundTrip(lat to lon, dist, orsKey, seed)
                if (pts.isNullOrEmpty()) """{"error":"routing failed — try a different start or distance"}"""
                else finalizeGeometry(pts)
            }
        }

        "route" -> {
            val wps = parseWaypoints(a)
            if (wps == null || wps.size < 2) """{"error":"need waypoints [[lat,lon],...] with at least 2 points"}"""
            else {
                val pts = RoutingService.route(wps, orsKey)
                if (pts.isNullOrEmpty()) """{"error":"routing failed"}"""
                else finalizeGeometry(pts)
            }
        }

        else -> """{"error":"unknown tool '${a.tool}'"}"""
    }

    /** Enrich routed points with elevation + computed distance/ascent; stash for "final". */
    private suspend fun finalizeGeometry(pts: List<Pair<Double, Double>>): String {
        val elevations = runCatching { ElevationService.lookup(pts) }.getOrDefault(List(pts.size) { null })
        val distance = AiRouteLogic.pathDistanceMeters(pts)
        val ascent = AiRouteLogic.ascentMeters(elevations)
        lastRoute = RoutedGeometry(pts, elevations, distance, ascent)
        return """{"ok":true,"distance_m":${distance.toInt()},"ascent_m":${ascent.toInt()},"points":${pts.size}}"""
    }

    private fun parseWaypoints(a: AiRouteLogic.Action): List<Pair<Double, Double>>? {
        val arr = a.args["waypoints"] as? JsonArray ?: return null
        return arr.mapNotNull { el ->
            val p = el as? JsonArray ?: return@mapNotNull null
            val lat = p.getOrNull(0)?.jsonPrimitive?.doubleOrNull
            val lon = p.getOrNull(1)?.jsonPrimitive?.doubleOrNull
            if (lat != null && lon != null) lat to lon else null
        }
    }

    private suspend fun callLlm(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildJsonObject {
                put("model", model)
                put("temperature", 0.2)
                putJsonArray("messages") {
                    messages.forEach { m -> addJsonObject { put("role", m.role); put("content", m.content) } }
                }
            }.toString()
            val conn = (URL(baseUrl.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                doOutput = true
                connectTimeout = 20_000
                readTimeout = 60_000
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val resp = conn.inputStream.bufferedReader().use { it.readText() }
            json.parseToJsonElement(resp).jsonObject["choices"]
                ?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject
                ?.get("content")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }

    companion object {
        private const val MAX_STEPS = 8

        private val SYSTEM_PROMPT = """
You plan running/walking routes by talking to the user and calling tools. You NEVER invent
coordinates — you obtain real geometry only from the tools.

Reply with EXACTLY ONE JSON object per turn, nothing else:
  {"action":"reply","reply":"<message to the user>"}            ask a question / chat
  {"action":"tool","tool":"<name>","args":{...}}                 call one tool
  {"action":"final","name":"<short route name>","reply":"<one line>"}  finalise the last route

Tools:
  current_location  args: {}                                    -> {"lat":..,"lon":..} or error
  geocode           args: {"query":"<place>"}                   -> {"lat":..,"lon":..,"label":..}
  round_trip        args: {"lat":..,"lon":..,"distance_m":<int>,"seed":<int optional>}
                    -> generates a loop of ~that length from the point
  route             args: {"waypoints":[[lat,lon],[lat,lon],...]}
                    -> road-following path through the waypoints
Each tool result comes back to you as a user message prefixed "TOOL_RESULT".

How to work:
- Figure out the start (use current_location for "here"/"from here"; geocode named places).
- If anything is ambiguous (which park? how far?), use action=reply to ask the user.
- For "Nk loop" use round_trip; for "to X" / "via X and Y" geocode them and use route.
- After a routing tool returns ok, you may action=final with a good name. Do NOT action=final
  before a routing tool has succeeded.
- Keep replies short and friendly. Distances are metres.
""".trim()
    }
}
