package com.wearosgpx.mobile.route

/**
 * Pure builders for the OpenRouteService directions payloads, kept out of [RoutingService]
 * so the route-shaping logic (preference + avoided features) is unit-testable without the
 * network. All coordinates are (lat, lon) but ORS wants [lon, lat].
 *
 * Shaping the AI can apply:
 *  - preference: "quiet" -> ORS "recommended" (greener/quieter paths), else "fastest" (direct).
 *  - avoid: any of steps / fords / ferries (anything else is ignored, so a hallucinated
 *    feature can't make ORS reject the whole request).
 */
object RoutingPayloads {

    /** ORS foot-walking only honours these avoid_features; filter to them. */
    val ALLOWED_AVOID = listOf("steps", "fords", "ferries")

    /** Map the AI's plain-English preference onto an ORS preference. */
    fun preferenceFor(prefer: String?): String = when (prefer?.trim()?.lowercase()) {
        "quiet", "scenic", "green", "recommended" -> "recommended"
        else -> "fastest"
    }

    /** Keep only avoid features ORS accepts, de-duped, order preserved. */
    fun normalizeAvoid(features: List<String>): List<String> {
        val wanted = features.map { it.trim().lowercase() }.toSet()
        return ALLOWED_AVOID.filter { it in wanted }
    }

    private fun avoidJson(avoid: List<String>): String? =
        normalizeAvoid(avoid).takeIf { it.isNotEmpty() }
            ?.joinToString(",", "[", "]") { "\"$it\"" }

    /** Directions through a sequence of (lat, lon) waypoints. */
    fun directions(
        waypoints: List<Pair<Double, Double>>,
        prefer: String? = null,
        avoid: List<String> = emptyList(),
    ): String {
        val coords = waypoints.joinToString(",") { "[${it.second},${it.first}]" }
        val avoidPart = avoidJson(avoid)?.let { ""","options":{"avoid_features":$it}""" } ?: ""
        return """{"coordinates":[$coords],""" +
            """"preference":"${preferenceFor(prefer)}"$avoidPart,""" +
            """"instructions":false}"""
    }

    /** A round-trip loop of ~[lengthMeters] from a single (lat, lon) start. */
    fun roundTrip(
        start: Pair<Double, Double>,
        lengthMeters: Double,
        seed: Int = 1,
        avoid: List<String> = emptyList(),
    ): String {
        val roundTrip = """"round_trip":{"length":${lengthMeters.toInt()},"points":5,"seed":$seed}"""
        val avoidPart = avoidJson(avoid)?.let { ""","avoid_features":$it""" } ?: ""
        return """{"coordinates":[[${start.second},${start.first}]],""" +
            """"options":{$roundTrip$avoidPart},""" +
            """"instructions":false}"""
    }
}
