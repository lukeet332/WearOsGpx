package com.wearosgpx.mobile.route

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches parkrun's public `events.json` (the GeoJSON feed its own event map uses) and
 * caches it for the session. This is an UNOFFICIAL, undocumented endpoint — parkrun has
 * changed it before and guards its data, so every call is best-effort: a failure returns
 * an empty list and the caller degrades gracefully (never a hard dependency).
 */
object ParkrunService {

    private const val EVENTS_URL = "https://images.parkrun.com/events.json"
    private const val UA = "WearOsGpx/1.0 (parkrun finder)"

    @Volatile private var cache: List<ParkrunParse.Event>? = null

    suspend fun events(): List<ParkrunParse.Event> = withContext(Dispatchers.IO) {
        cache?.let { return@withContext it }
        runCatching { ParkrunParse.events(httpGet(EVENTS_URL)) }
            .getOrDefault(emptyList())
            .also { if (it.isNotEmpty()) cache = it }
    }

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json")
            connectTimeout = 15_000
            readTimeout = 20_000
        }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }
}
