package com.wearosgpx.mobile.route

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPOutputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max

/**
 * Builds the baked vector [BaseMap] for the corridor around a route by querying OSM
 * (Overpass) for nearby roads/paths/water, simplifying it, capping the size, and
 * gzipping it. Returns null if there's nothing to show or the request fails — the
 * watch just falls back to the breadcrumb-only view.
 */
object BaseMapService {

    private const val UA = "WearOsGpx/1.0 (basemap)"
    private const val OVERPASS = "https://overpass-api.de/api/interpreter"
    private const val MARGIN_KM = 3.0          // map shown this far beyond the route ("if lost")
    private const val SIMPLIFY_EPS = 0.00006   // ~6 m Douglas–Peucker tolerance
    private const val MAX_POINTS = 16_000       // total coordinate budget across features
    private val json = Json { ignoreUnknownKeys = true }

    private const val T_MAJOR = 0
    private const val T_MINOR = 1
    private const val T_PATH = 2
    private const val T_WATER = 3

    /** Build + gzip the basemap for [routePoints] (lat,lon). Null if empty/failed. */
    suspend fun build(routePoints: List<Pair<Double, Double>>): ByteArray? = withContext(Dispatchers.IO) {
        if (routePoints.size < 2) return@withContext null
        val lats = routePoints.map { it.first }
        val lons = routePoints.map { it.second }
        val midLat = (lats.min() + lats.max()) / 2
        val dLat = MARGIN_KM / 111.0
        val dLon = MARGIN_KM / (111.0 * cos(Math.toRadians(midLat)).coerceAtLeast(0.01))
        val bbox = "${lats.min() - dLat},${lons.min() - dLon},${lats.max() + dLat},${lons.max() + dLon}"

        val query = "[out:json][timeout:90];(" +
            "way[\"highway\"~\"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|pedestrian|footway|path|cycleway|track|steps|bridleway)(_link)?$\"]($bbox);" +
            "way[\"waterway\"~\"^(river|stream|canal)$\"]($bbox);" +
            "way[\"natural\"=\"water\"]($bbox);" +
            ");out geom;"

        runCatching {
            val body = overpass(query)
            val raw = json.parseToJsonElement(body).jsonObject["elements"]?.jsonArray ?: return@withContext null
            val features = ArrayList<MapFeature>()
            for (el in raw) {
                val o = el.jsonObject
                val tags = o["tags"]?.jsonObject ?: continue
                val type = classify(
                    tags["highway"]?.jsonPrimitive?.content,
                    tags["waterway"]?.jsonPrimitive?.content,
                    tags["natural"]?.jsonPrimitive?.content,
                ) ?: continue
                val geom = o["geometry"]?.jsonArray ?: continue
                val pts = geom.mapNotNull { g ->
                    val go = g.jsonObject
                    val la = go["lat"]?.jsonPrimitive?.content?.toDoubleOrNull()
                    val lo = go["lon"]?.jsonPrimitive?.content?.toDoubleOrNull()
                    if (la != null && lo != null) la to lo else null
                }
                if (pts.size < 2) continue
                val simplified = simplify(pts, SIMPLIFY_EPS)
                features += MapFeature(type, simplified.flatMap { listOf(round5(it.first), round5(it.second)) })
            }
            if (features.isEmpty()) return@withContext null
            val capped = capToBudget(features)
            gzip(json.encodeToString(BaseMap(capped)).toByteArray(Charsets.UTF_8))
        }.getOrNull()
    }

    private fun classify(highway: String?, waterway: String?, natural: String?): Int? = when {
        natural == "water" || waterway != null -> T_WATER
        highway == null -> null
        highway.startsWith("motorway") || highway.startsWith("trunk") ||
            highway.startsWith("primary") || highway.startsWith("secondary") -> T_MAJOR
        highway.startsWith("tertiary") || highway == "unclassified" ||
            highway == "residential" || highway == "living_street" || highway == "pedestrian" -> T_MINOR
        else -> T_PATH
    }

    /** Keep the most useful features within the coordinate budget (major → minor → water → paths). */
    private fun capToBudget(features: List<MapFeature>): List<MapFeature> {
        val order = listOf(T_MAJOR, T_MINOR, T_WATER, T_PATH)
        val out = ArrayList<MapFeature>(features.size)
        var used = 0
        for (t in order) {
            for (f in features) {
                if (f.t != t) continue
                val n = f.c.size / 2
                if (used + n > MAX_POINTS) continue
                out += f; used += n
            }
        }
        return out
    }

    // --- Douglas–Peucker (lat/lon as planar; fine at these scales) ---

    private fun simplify(points: List<Pair<Double, Double>>, eps: Double): List<Pair<Double, Double>> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size)
        keep[0] = true; keep[points.size - 1] = true
        rdp(points, 0, points.size - 1, eps, keep)
        return points.filterIndexed { i, _ -> keep[i] }
    }

    private fun rdp(p: List<Pair<Double, Double>>, start: Int, end: Int, eps: Double, keep: BooleanArray) {
        if (end <= start + 1) return
        var maxD = 0.0; var idx = -1
        for (i in start + 1 until end) {
            val d = perpDist(p[i], p[start], p[end])
            if (d > maxD) { maxD = d; idx = i }
        }
        if (maxD > eps && idx != -1) {
            keep[idx] = true
            rdp(p, start, idx, eps, keep)
            rdp(p, idx, end, eps, keep)
        }
    }

    private fun perpDist(pt: Pair<Double, Double>, a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val (x, y) = pt; val (x1, y1) = a; val (x2, y2) = b
        val dx = x2 - x1; val dy = y2 - y1
        if (dx == 0.0 && dy == 0.0) return abs(x - x1) + abs(y - y1)
        val t = (((x - x1) * dx + (y - y1) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        val px = x1 + t * dx; val py = y1 + t * dy
        return max(abs(x - px), abs(y - py))
    }

    private fun round5(v: Double): Double = Math.round(v * 1e5) / 1e5

    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
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
