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
import kotlin.math.abs

/** The route operations the agent can perform on the user's saved routes (implemented by the UI). */
interface RouteOps {
    data class Info(val fileName: String, val name: String, val distanceMeters: Double)
    suspend fun list(): List<Info>
    suspend fun delete(fileName: String): Boolean   // -> recycle bin + remove from watch
    suspend fun rename(fileName: String, newName: String): Boolean
    suspend fun share(fileName: String): Boolean     // -> opens the system share sheet
}

/**
 * Conversational route-planning agent. The LLM (any OpenAI-compatible provider) drives a
 * tool-calling loop over our REAL APIs — it never invents coordinates: it asks for the
 * current location, geocodes places, lists/deletes saved routes, and gets actual road
 * geometry from OpenRouteService. round_trip auto-corrects toward the requested distance and
 * the actual distance is fed back so the agent can iterate. It can ask the user clarifying
 * questions, and finalises (create OR update) for preview + save. Pure logic in [AiRouteLogic].
 */
class AiRouteAgent(
    private val apiKey: String,
    private val model: String,
    private val baseUrl: String,
    private val orsKey: String,
    private val currentLocation: Pair<Double, Double>?,
    private val routeOps: RouteOps,
) {
    data class RoutedGeometry(
        val points: List<Pair<Double, Double>>,
        val elevations: List<Double?>,
        val distanceM: Double,
        val ascentM: Double,
    )

    sealed interface Turn {
        data class Reply(val text: String) : Turn
        /** [replaceFileName] != null => update that existing route in place (else create new). */
        data class RouteReady(val name: String, val geometry: RoutedGeometry, val replaceFileName: String? = null) : Turn
        data class Failed(val text: String) : Turn
    }

    private data class Msg(val role: String, val content: String)

    private val json = Json { ignoreUnknownKeys = true }
    private val messages = mutableListOf(Msg("system", SYSTEM_PROMPT))
    private var lastRoute: RoutedGeometry? = null

    /** Routes surfaced by the last search_routes call, so load_route can resolve an id. */
    private val discovered = mutableMapOf<Long, RouteDiscoveryService.DiscoveredRoute>()

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
                    return Turn.RouteReady(action.name?.takeIf { it.isNotBlank() } ?: "AI route", g, action.replace?.takeIf { it.isNotBlank() })
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
                val avoid = AiRouteLogic.argStringList(a, "avoid")
                val pts = targetedRoundTrip(lat to lon, dist, seed, avoid)   // auto-fits toward the target
                if (pts.isNullOrEmpty()) """{"error":"routing failed — try a different start or distance"}"""
                else finalizeGeometry(pts)
            }
        }

        "list_routes" -> {
            val rs = routeOps.list()
            """{"routes":[""" + rs.joinToString(",") {
                """{"file":${JsonPrimitive(it.fileName)},"name":${JsonPrimitive(it.name)},"distance_m":${it.distanceMeters.toInt()}}"""
            } + "]}"
        }

        "delete_route" -> {
            val file = AiRouteLogic.argString(a, "file")
            if (file.isNullOrBlank()) """{"error":"missing file — call list_routes first for exact file names"}"""
            else if (routeOps.delete(file)) """{"ok":true,"deleted":${JsonPrimitive(file)},"note":"moved to the recycle bin (recoverable for 2 days)"}"""
            else """{"error":"no route with file '$file'"}"""
        }

        "route" -> {
            val wps = parseWaypoints(a)
            if (wps == null || wps.size < 2) """{"error":"need waypoints [[lat,lon],...] with at least 2 points"}"""
            else {
                val prefer = AiRouteLogic.argString(a, "prefer")
                val avoid = AiRouteLogic.argStringList(a, "avoid").ifEmpty { listOf("steps", "fords") }
                val pts = RoutingService.route(wps, orsKey, prefer, avoid)
                if (pts.isNullOrEmpty()) """{"error":"routing failed"}"""
                else finalizeGeometry(pts)
            }
        }

        "search_routes" -> {
            val q = AiRouteLogic.argString(a, "query")
            val found = if (!q.isNullOrBlank()) RouteDiscoveryService.searchByName(q)
            else currentLocation?.let { RouteDiscoveryService.searchNearby(it.first, it.second) } ?: emptyList()
            discovered.clear()
            found.forEach { discovered[it.id] = it }
            if (found.isEmpty()) """{"routes":[],"note":"nothing on OpenStreetMap for that — for a one-off/annual race, ask the user for the official GPX link and use import_gpx_url"}"""
            else """{"routes":[""" + found.take(20).joinToString(",") {
                """{"id":${it.id},"name":${JsonPrimitive(it.name)},"activity":${JsonPrimitive(it.activity)}}"""
            } + "]}"
        }

        "find_parkrun" -> {
            val q = AiRouteLogic.argString(a, "query")
            val events = ParkrunService.events()
            if (events.isEmpty()) """{"error":"couldn't reach the parkrun event list"}"""
            else {
                val matches = if (!q.isNullOrBlank()) ParkrunParse.search(events, q)
                else currentLocation?.let { ParkrunParse.nearest(events, it.first, it.second, 6) } ?: emptyList()
                """{"parkruns":[""" + matches.take(6).joinToString(",") {
                    """{"name":${JsonPrimitive(it.longName)},"location":${JsonPrimitive(it.location)},"lat":${it.lat},"lon":${it.lon},"distance_m":${it.distanceMeters}}"""
                } + "]}"
            }
        }

        "routes_near" -> {
            val lat = AiRouteLogic.argDouble(a, "lat")
            val lon = AiRouteLogic.argDouble(a, "lon")
            val target = AiRouteLogic.argDouble(a, "distance_m").let { if (it.isNaN()) 5000.0 else it }
            if (lat.isNaN() || lon.isNaN()) """{"error":"need lat and lon"}"""
            else {
                val loops = RouteDiscoveryService.findLoopsNear(lat, lon, target)
                loops.forEach { discovered[it.route.id] = it.route }
                """{"loops":[""" + loops.take(5).joinToString(",") {
                    """{"id":${it.route.id},"name":${JsonPrimitive(it.route.name)},"distance_m":${it.distanceMeters.toInt()}}"""
                } + "]}"
            }
        }

        "load_route" -> {
            val id = AiRouteLogic.argDouble(a, "id").let { if (it.isNaN()) null else it.toLong() }
            val route = id?.let { discovered[it] }
            if (route == null) """{"error":"unknown id — call search_routes first and use an id it returned"}"""
            else {
                val pts = RouteDiscoveryService.buildPoints(route)
                if (pts.isNullOrEmpty()) """{"error":"couldn't assemble that route's geometry"}"""
                else finalizeGeometry(pts, withElevation = false)
            }
        }

        "import_gpx_url" -> {
            val url = AiRouteLogic.argString(a, "url")
            if (url.isNullOrBlank()) """{"error":"missing url (must be a direct http(s) link to a .gpx)"}"""
            else {
                val pts = RouteDiscoveryService.importGpxUrl(url)
                if (pts.isNullOrEmpty()) """{"error":"couldn't read a track from that URL — make sure it's a direct link to a GPX file"}"""
                else finalizeGeometry(pts, withElevation = false)
            }
        }

        "rename_route" -> {
            val file = AiRouteLogic.argString(a, "file")
            val newName = AiRouteLogic.argString(a, "name")
            if (file.isNullOrBlank() || newName.isNullOrBlank()) """{"error":"need file (from list_routes) and name"}"""
            else if (routeOps.rename(file, newName)) """{"ok":true,"renamed":${JsonPrimitive(file)}}"""
            else """{"error":"no route with file '$file'"}"""
        }

        "share_route" -> {
            val file = AiRouteLogic.argString(a, "file")
            if (file.isNullOrBlank()) """{"error":"missing file — call list_routes first"}"""
            else if (routeOps.share(file)) """{"ok":true,"note":"opened the share sheet"}"""
            else """{"error":"no local copy of '$file' to share"}"""
        }

        else -> """{"error":"unknown tool '${a.tool}'"}"""
    }

    /**
     * ORS round_trip's `length` is approximate and tends to overshoot, so scale the requested
     * length toward the user's [targetM] over a few attempts and keep the closest result. This
     * gets "5k" near 5k instead of, say, 6.8k; the actual distance is still fed back so the
     * agent can iterate further (e.g. a different seed) if needed.
     */
    private suspend fun targetedRoundTrip(start: Pair<Double, Double>, targetM: Double, seed: Int, avoid: List<String> = emptyList()): List<Pair<Double, Double>>? {
        var length = targetM
        var best: List<Pair<Double, Double>>? = null
        var bestErr = Double.MAX_VALUE
        repeat(3) {
            val pts = RoutingService.roundTrip(start, length, orsKey, seed, avoid) ?: return best
            val actual = AiRouteLogic.pathDistanceMeters(pts)
            val err = if (targetM > 0) abs(actual - targetM) / targetM else 0.0
            if (err < bestErr) { bestErr = err; best = pts }
            if (err <= 0.08 || actual <= 0) return best
            length *= targetM / actual   // correct the requested length toward the target
        }
        return best
    }

    /**
     * Enrich routed points with elevation + computed distance/ascent; stash for "final".
     * [withElevation] is skipped for discovered/imported routes — they can be thousands of
     * points, and hitting the elevation API per point would be slow and hammer the service.
     */
    private suspend fun finalizeGeometry(pts: List<Pair<Double, Double>>, withElevation: Boolean = true): String {
        val elevations =
            if (withElevation) runCatching { ElevationService.lookup(pts) }.getOrDefault(List(pts.size) { null })
            else List(pts.size) { null }
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
  {"action":"final","name":"<short route name>","reply":"<one line>"}  finalise a NEW route
  {"action":"final","name":"...","replace":"<file>"}            finalise an UPDATE to an existing route

Tools:
  current_location  args: {}                                    -> {"lat":..,"lon":..} or error
  geocode           args: {"query":"<place>"}                   -> {"lat":..,"lon":..,"label":..}
  round_trip        args: {"lat":..,"lon":..,"distance_m":<int>,"seed":<int optional>,"avoid":[..] optional}
                    -> a loop AUTO-FITTED toward distance_m; returns the ACTUAL distance_m
  route             args: {"waypoints":[[lat,lon],...],"prefer":"quiet|direct" optional,"avoid":[..] optional}
                    -> road path through the waypoints
  search_routes     args: {"query":"<name>"} OR {}              -> real OSM routes [{"id","name","activity"}]
                    (query = search by name e.g. "South West Coast Path"; {} = named routes near you)
  load_route        args: {"id":<id from search_routes/routes_near>} -> builds that route's geometry
  routes_near       args: {"lat":..,"lon":..,"distance_m":<int>}   -> OSM loops near a point of ~distance_m
                    that pass over it [{"id","name","distance_m"}] (for "find the course at this spot")
  find_parkrun      args: {"query":"<name>"} OR {}              -> matching parkruns [{"name","location","lat","lon","distance_m"}]
                    (query = by name e.g. "Bushy"; {} = parkruns near you. LOCATION only, not the course.)
  import_gpx_url    args: {"url":"<direct .gpx link>"}          -> imports a route from a GPX the user links
  list_routes       args: {}                                    -> {"routes":[{"file","name","distance_m"}]}
  delete_route      args: {"file":"<file from list_routes>"}    -> moves it to the recycle bin (recoverable)
  rename_route      args: {"file":"<file>","name":"<new name>"} -> renames a saved route
  share_route       args: {"file":"<file>"}                     -> opens the system share sheet
Shaping: avoid can be any of ["steps","fords","ferries"]; prefer "quiet" favours greener/quieter
paths, "direct" is the default. Use them when the user asks (hilly/flat is NOT supported — say so).
Each tool result comes back to you as a user message prefixed "TOOL_RESULT".

How to work:
- Figure out the start (use current_location for "here"/"from here"; geocode named places).
- If anything is ambiguous (which park? how far?), use action=reply to ask the user.
- For "Nk loop" use round_trip; for "to X" / "via X and Y" geocode them and use route.
- KNOWN/NAMED routes (trails, well-known long-distance paths): try search_routes by name and
  load_route the best match BEFORE building one yourself.
- PARKRUN: call find_parkrun (it returns LOCATION only, not the course). If several match,
  ask which. Then, with the start lat/lon:
    1. try search_routes with the parkrun's name and load_route the best match (the real course);
    2. else call routes_near with the start lat/lon and the returned distance_m; if it returns a
       loop, load_route the FIRST (closest) one and action=final with a reply that you couldn't
       find an exact parkrun course on OpenStreetMap but found a ~Nk loop over the start that's
       LIKELY it — ask them to check the preview and confirm it looks right;
    3. else (routes_near is empty too) APOLOGISE: say you couldn't find the course on
       OpenStreetMap, and explain parkrun has deliberately locked down its data/APIs to prevent
       exactly this. Do NOT suggest importing a GPX, and do NOT invent a loop.
- ONE-OFF or ANNUAL RACES (e.g. a specific year's marathon): these change yearly and are NOT
  reliably in any database — do NOT invent the course. Try search_routes; if nothing fits, be
  honest you can't reproduce an exact dated course from memory.
- NEVER proactively suggest the user import a GPX file — they already know they can; it's annoying.
  Only use import_gpx_url if the user themselves provides a GPX link.
- MATCH THE TARGET: round_trip returns the ACTUAL distance_m. If it's still off the user's
  requested distance by more than ~8%, call round_trip AGAIN (adjust distance_m, or a new seed)
  before finalising. Only action=final when the actual distance is close to what they asked.
- UPDATE: to change an existing route ("make my 5k a 10k"), call list_routes, find its file,
  build the new geometry (round_trip/route), then action=final with "replace":"<file>".
- DELETE: use delete_route (it's recoverable from the recycle bin, so it's safe). RENAME/SHARE
  with rename_route / share_route (these act immediately — they aren't a final).
- After a routing/load/import tool returns ok and the distance is close, action=final. Never
  action=final before geometry exists. Keep replies short and friendly. Distances are metres.
""".trim()
    }
}
