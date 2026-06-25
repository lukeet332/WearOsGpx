package com.wearosgpx.mobile.route

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.cos
import kotlin.math.hypot
import org.xmlpull.v1.XmlPullParser

/**
 * Finds open-source routes from OpenStreetMap and turns them into GPX:
 *  - search by name via Waymarked Trails (per-activity hosts);
 *  - search near a location via the Overpass API (route relations in a bbox);
 *  - fetch a route's geometry via Overpass and assemble it into an ordered track.
 *
 * All free, no API key (OSM data, ODbL). A descriptive User-Agent is required.
 */
object RouteDiscoveryService {

    private const val UA = "WearOsGpx/1.0 (route discovery)"
    private const val OVERPASS = "https://overpass-api.de/api/interpreter"
    private const val MAX_POINTS = 3000          // decimate huge trails for the watch
    private val json = Json { ignoreUnknownKeys = true }

    data class DiscoveredRoute(val id: Long, val name: String, val activity: String)

    /** Waymarked activity host → label. */
    private val NAME_SOURCES = listOf(
        "hiking" to "Hiking",
        "cycling" to "Cycling",
        "mtb" to "MTB",
    )

    /** Search named routes across activities. */
    suspend fun searchByName(query: String): List<DiscoveredRoute> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val q = URLEncoder.encode(query, "UTF-8")
        val seen = HashSet<Long>()
        val out = ArrayList<DiscoveredRoute>()
        for ((host, label) in NAME_SOURCES) {
            val url = "https://$host.waymarkedtrails.org/api/v1/list/search?query=$q&limit=15"
            runCatching {
                val body = httpGet(url)
                json.parseToJsonElement(body).jsonObject["results"]?.jsonArray?.forEach { el ->
                    val o = el.jsonObject
                    val id = o["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@forEach
                    val name = o["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return@forEach
                    if (seen.add(id)) out += DiscoveredRoute(id, name, label)
                }
            }
        }
        out
    }

    /** Find named route relations within ~[radiusKm] of a point. */
    suspend fun searchNearby(lat: Double, lon: Double, radiusKm: Double = 15.0): List<DiscoveredRoute> =
        withContext(Dispatchers.IO) {
            val dLat = radiusKm / 111.0
            val dLon = radiusKm / (111.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.01))
            val bbox = "${lat - dLat},${lon - dLon},${lat + dLat},${lon + dLon}"
            val query = "[out:json][timeout:60];" +
                "relation[\"route\"~\"hiking|foot|running|bicycle|mtb\"][\"name\"]($bbox);" +
                "out tags 60;"
            runCatching {
                val body = overpass(query)
                val seen = HashSet<Long>()
                json.parseToJsonElement(body).jsonObject["elements"]?.jsonArray?.mapNotNull { el ->
                    val o = el.jsonObject
                    val id = o["id"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null
                    val tags = o["tags"]?.jsonObject
                    val name = tags?.get("name")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    if (!seen.add(id)) return@mapNotNull null
                    DiscoveredRoute(id, name, (tags["route"]?.jsonPrimitive?.content ?: "route").replaceFirstChar { it.uppercase() })
                } ?: emptyList()
            }.getOrDefault(emptyList())
        }

    /** Fetch a route relation's geometry and assemble it into an ordered GPX track. */
    suspend fun buildGpx(route: DiscoveredRoute): String? =
        buildPoints(route)?.let { GpxBuilder.build(route.name, it) }

    /** Fetch + assemble a discovered route's ordered (lat, lon) track (for preview/save). */
    suspend fun buildPoints(route: DiscoveredRoute): List<Pair<Double, Double>>? = withContext(Dispatchers.IO) {
        val query = "[out:json][timeout:90];rel(${route.id});>>;way._;out geom;"
        runCatching {
            val body = overpass(query)
            val ways = json.parseToJsonElement(body).jsonObject["elements"]?.jsonArray
                ?.mapNotNull { el ->
                    el.jsonObject["geometry"]?.jsonArray?.mapNotNull { g ->
                        val o = g.jsonObject
                        val la = o["lat"]?.jsonPrimitive?.content?.toDoubleOrNull()
                        val lo = o["lon"]?.jsonPrimitive?.content?.toDoubleOrNull()
                        if (la != null && lo != null) la to lo else null
                    }?.takeIf { it.size >= 2 }
                } ?: emptyList()
            if (ways.isEmpty()) return@withContext null
            decimate(assemble(ways)).takeIf { it.size >= 2 }
        }.getOrNull()
    }

    /**
     * Download a GPX file from a public [url] and pull out its track. Lets the AI ingest a
     * route the user already has a link to — e.g. an official race course or a Strava export
     * — when no OSM relation exists (annual races change yearly and aren't reliably mapped).
     */
    suspend fun importGpxUrl(url: String): List<Pair<Double, Double>>? = withContext(Dispatchers.IO) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return@withContext null
        runCatching {
            decimate(parseGpxTrack(httpGet(url))).takeIf { it.size >= 2 }
        }.getOrNull()
    }

    /** Parse trk/rte points out of a GPX document (wpt markers only if there's no track). */
    private fun parseGpxTrack(xml: String): List<Pair<Double, Double>> {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(xml.reader())
        }
        val track = ArrayList<Pair<Double, Double>>()
        val waypoints = ArrayList<Pair<Double, Double>>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                if (lat != null && lon != null) when (parser.name) {
                    "trkpt", "rtept" -> track.add(lat to lon)
                    "wpt" -> waypoints.add(lat to lon)
                }
            }
            event = parser.next()
        }
        return if (track.isNotEmpty()) track else waypoints
    }

    // --- geometry assembly ---

    /** Greedily chain unordered way segments into one polyline, flipping to connect endpoints. */
    internal fun assemble(ways: List<List<Pair<Double, Double>>>): List<Pair<Double, Double>> {
        val remaining = ways.toMutableList()
        val chain = ArrayList(remaining.removeAt(0))
        while (remaining.isNotEmpty()) {
            val tail = chain.last()
            var best = 0
            var bestFlip = false
            var bestDist = Double.MAX_VALUE
            for (i in remaining.indices) {
                val w = remaining[i]
                val dStart = sqDist(tail, w.first())
                val dEnd = sqDist(tail, w.last())
                if (dStart < bestDist) { bestDist = dStart; best = i; bestFlip = false }
                if (dEnd < bestDist) { bestDist = dEnd; best = i; bestFlip = true }
            }
            val next = remaining.removeAt(best)
            chain.addAll(if (bestFlip) next.asReversed() else next)
        }
        return chain
    }

    private fun sqDist(a: Pair<Double, Double>, b: Pair<Double, Double>): Double =
        hypot(a.first - b.first, a.second - b.second)

    /** Cap point count so very long trails stay light on the watch. */
    private fun decimate(points: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        if (points.size <= MAX_POINTS) return points
        val stride = points.size / MAX_POINTS + 1
        val out = ArrayList<Pair<Double, Double>>(MAX_POINTS + 1)
        var i = 0
        while (i < points.size) { out.add(points[i]); i += stride }
        if (out.last() != points.last()) out.add(points.last())
        return out
    }

    // --- http ---

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", UA)
            connectTimeout = 15_000
            readTimeout = 20_000
        }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun overpass(query: String): String {
        val conn = (URL(OVERPASS).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connectTimeout = 15_000
            readTimeout = 95_000
            doOutput = true
        }
        conn.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }
}
