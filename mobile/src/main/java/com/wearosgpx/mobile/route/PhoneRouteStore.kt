package com.wearosgpx.mobile.route

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Phone-local copy of routes the user created/sent, so they can be shared or
 * downloaded later (the watch index only carries metadata, not the GPX bytes).
 */
object PhoneRouteStore {

    private fun dir(context: Context): File {
        val d = context.getExternalFilesDir("routes") ?: File(context.filesDir, "routes")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun save(context: Context, fileName: String, bytes: ByteArray): File {
        val file = File(dir(context), safeName(fileName))
        file.writeBytes(bytes)
        return file
    }

    fun list(context: Context): List<File> =
        dir(context).listFiles()
            ?.filter { it.isFile && it.extension.equals("gpx", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?: emptyList()

    fun delete(context: Context, fileName: String): Boolean =
        File(dir(context), safeName(fileName)).let { it.exists() && it.delete() }

    /** Returns the local GPX file for a route name, if the phone has a copy. */
    fun fileFor(context: Context, fileName: String): File? =
        File(dir(context), safeName(fileName)).takeIf { it.exists() }

    fun safeName(name: String): String {
        val cleaned = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (cleaned.endsWith(".gpx", ignoreCase = true)) cleaned else "$cleaned.gpx"
    }

    // --- Local basemap (gzipped JSON) kept next to the GPX as <base>.map ---

    private val json = Json { ignoreUnknownKeys = true }

    private fun mapFile(context: Context, gpxFileName: String): File {
        val safe = safeName(gpxFileName)
        val base = if (safe.endsWith(".gpx", true)) safe.dropLast(4) else safe
        return File(dir(context), "$base.map")
    }

    fun saveBaseMap(context: Context, gpxFileName: String, gzBytes: ByteArray) {
        mapFile(context, gpxFileName).writeBytes(gzBytes)
    }

    fun deleteBaseMap(context: Context, gpxFileName: String) {
        mapFile(context, gpxFileName).takeIf { it.exists() }?.delete()
    }

    /** Load + ungzip + decode the local basemap for a route, or null if none. */
    fun loadBaseMap(context: Context, gpxFileName: String): BaseMap? {
        val file = mapFile(context, gpxFileName)
        if (!file.exists()) return null
        return runCatching {
            val data = GZIPInputStream(file.inputStream()).use { gz ->
                ByteArrayOutputStream().also { gz.copyTo(it) }.toByteArray()
            }
            json.decodeFromString<BaseMap>(data.toString(Charsets.UTF_8))
        }.getOrNull()
    }
}
