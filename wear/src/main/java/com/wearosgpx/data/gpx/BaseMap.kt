package com.wearosgpx.data.gpx

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Baked vector basemap (roads/paths/water around a route), built on the phone and
 * pushed alongside the GPX. Mirrors `com.wearosgpx.mobile.route.BaseMap` — keep in
 * sync. [MapFeature.t]: 0 major road, 1 minor road, 2 path, 3 water. [MapFeature.c]:
 * flattened [lat, lon, lat, lon, …].
 */
@Serializable
data class BaseMap(val features: List<MapFeature> = emptyList())

@Serializable
data class MapFeature(val t: Int, val c: List<Double>)

/** Persists/loads the gzipped basemap next to its route as `<base>.map`. */
object BaseMapStore {

    private val json = Json { ignoreUnknownKeys = true }

    fun mapFile(context: Context, gpxFileName: String): File =
        File(RouteCatalog.routesDir(context), baseName(gpxFileName) + ".map")

    /** Save the gzipped bytes exactly as received from the phone. */
    fun save(context: Context, gpxFileName: String, gzBytes: ByteArray) {
        mapFile(context, gpxFileName).writeBytes(gzBytes)
    }

    fun delete(context: Context, gpxFileName: String) {
        mapFile(context, gpxFileName).takeIf { it.exists() }?.delete()
    }

    /** Load + ungzip + decode the basemap for a route, or null if none. */
    fun load(context: Context, gpxFileName: String?): BaseMap? {
        if (gpxFileName == null) return null
        val file = mapFile(context, gpxFileName)
        if (!file.exists()) return null
        return runCatching {
            val data = GZIPInputStream(file.inputStream()).use { gz ->
                ByteArrayOutputStream().also { gz.copyTo(it) }.toByteArray()
            }
            json.decodeFromString<BaseMap>(data.toString(Charsets.UTF_8))
        }.getOrNull()
    }

    private fun baseName(gpxFileName: String): String =
        if (gpxFileName.endsWith(".gpx", ignoreCase = true)) gpxFileName.dropLast(4) else gpxFileName
}
