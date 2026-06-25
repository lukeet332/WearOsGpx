package com.wearosgpx.mobile.route

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure logic for the AI route agent: parsing the model's JSON "action" protocol and the
 * geometry math (distance / ascent) used in the preview. No network, no Android — unit
 * tested. The model replies with one JSON object per turn:
 *   {"action":"reply","reply":"..."}                     ask the user / chat
 *   {"action":"tool","tool":"<name>","args":{...}}        run one of our real APIs
 *   {"action":"final","name":"...","reply":"..."}         finalise the last routed geometry
 * Anything that isn't valid JSON is treated as a plain chat reply (robustness).
 */
object AiRouteLogic {

    private val json = Json { ignoreUnknownKeys = true }

    enum class Kind { REPLY, TOOL, FINAL }

    data class Action(
        val kind: Kind,
        val reply: String? = null,
        val tool: String? = null,
        val args: JsonObject = JsonObject(emptyMap()),
        val name: String? = null,
    )

    fun parseAction(raw: String): Action {
        val text = raw.trim()
        extractJson(text)?.let { jsonStr ->
            runCatching {
                val obj = json.parseToJsonElement(jsonStr).jsonObject
                when (obj["action"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
                    "tool" -> return Action(
                        Kind.TOOL,
                        tool = obj["tool"]?.jsonPrimitive?.contentOrNull,
                        args = (obj["args"] as? JsonObject) ?: JsonObject(emptyMap()),
                        reply = obj["reply"]?.jsonPrimitive?.contentOrNull,
                    )
                    "final" -> return Action(
                        Kind.FINAL,
                        name = obj["name"]?.jsonPrimitive?.contentOrNull,
                        reply = obj["reply"]?.jsonPrimitive?.contentOrNull,
                    )
                    "reply" -> return Action(
                        Kind.REPLY,
                        reply = obj["reply"]?.jsonPrimitive?.contentOrNull ?: text,
                    )
                }
            }
        }
        return Action(Kind.REPLY, reply = text)
    }

    fun argDouble(a: Action, key: String): Double =
        a.args[key]?.jsonPrimitive?.doubleOrNull ?: Double.NaN

    fun argString(a: Action, key: String): String? =
        a.args[key]?.jsonPrimitive?.contentOrNull

    /** Pull the first JSON object out of [text], unwrapping ``` / ```json fences. */
    private fun extractJson(text: String): String? {
        var t = text
        Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(t)?.let { t = it.groupValues[1].trim() }
        val start = t.indexOf('{')
        val end = t.lastIndexOf('}')
        return if (start >= 0 && end > start) t.substring(start, end + 1) else null
    }

    // ---- geometry math (for the preview) ----

    fun pathDistanceMeters(points: List<Pair<Double, Double>>): Double {
        var sum = 0.0
        for (i in 1 until points.size) sum += haversine(points[i - 1], points[i])
        return sum
    }

    /** Total positive elevation gain; nulls (no data) are skipped, not treated as 0. */
    fun ascentMeters(elevations: List<Double?>): Double {
        var sum = 0.0
        var prev: Double? = null
        for (e in elevations) {
            if (e != null) {
                val p = prev
                if (p != null && e > p) sum += e - p
                prev = e
            }
        }
        return sum
    }

    private fun haversine(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.first - a.first)
        val dLon = Math.toRadians(b.second - a.second)
        val la1 = Math.toRadians(a.first)
        val la2 = Math.toRadians(b.first)
        val h = sin(dLat / 2).pow(2) + cos(la1) * cos(la2) * sin(dLon / 2).pow(2)
        return 2 * r * asin(min(1.0, sqrt(h)))
    }
}
