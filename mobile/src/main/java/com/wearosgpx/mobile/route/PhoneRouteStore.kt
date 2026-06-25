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

    // --- Recycle bin: deletes/updates move the old GPX to a timestamped trash dir so
    //     mistakes (incl. AI ones) are recoverable; entries auto-purge after TRASH_MAX_AGE_MS. ---

    const val TRASH_MAX_AGE_MS: Long = 2L * 24 * 60 * 60 * 1000   // 2 days
    private const val TRASH_SEP = "__"

    private fun trashDir(context: Context): File {
        val d = context.getExternalFilesDir("routes_trash") ?: File(context.filesDir, "routes_trash")
        if (!d.exists()) d.mkdirs()
        return d
    }

    // pure helpers (unit-tested)
    fun trashEntryName(fileName: String, epochMillis: Long): String = "$epochMillis$TRASH_SEP${safeName(fileName)}"
    fun originalNameOf(trashEntry: String): String = trashEntry.substringAfter(TRASH_SEP, trashEntry)
    fun timestampOf(trashEntry: String): Long? = trashEntry.substringBefore(TRASH_SEP).toLongOrNull()
    fun isExpired(trashEntry: String, nowMillis: Long, maxAgeMillis: Long): Boolean {
        val ts = timestampOf(trashEntry) ?: return true   // unparseable -> purge
        return nowMillis - ts > maxAgeMillis
    }

    /** A trashed route: its trash file name, the original route name, and when it was deleted. */
    data class TrashedRoute(val entry: String, val originalName: String, val deletedAt: Long)

    /** Move a route's GPX to the recycle bin (versioned by timestamp). Best-effort. */
    fun moveToTrash(context: Context, fileName: String, now: Long = System.currentTimeMillis()) {
        val src = File(dir(context), safeName(fileName))
        if (!src.exists()) return
        val dest = File(trashDir(context), trashEntryName(fileName, now))
        runCatching { src.copyTo(dest, overwrite = true); src.delete() }
    }

    fun listTrash(context: Context): List<TrashedRoute> =
        trashDir(context).listFiles()
            ?.filter { it.isFile && it.name.contains(TRASH_SEP) }
            ?.map { TrashedRoute(it.name, originalNameOf(it.name), timestampOf(it.name) ?: 0L) }
            ?.sortedByDescending { it.deletedAt }
            ?: emptyList()

    /** Restore a trashed entry back to the routes dir; returns the restored file name, or null. */
    fun restoreFromTrash(context: Context, entry: String): String? {
        val src = File(trashDir(context), entry)
        if (!src.exists()) return null
        val dest = File(dir(context), safeName(originalNameOf(entry)))
        return runCatching { src.copyTo(dest, overwrite = true); src.delete(); dest.name }.getOrNull()
    }

    /** Delete trash entries older than [maxAgeMillis]. Returns how many were purged. */
    fun purgeTrash(context: Context, maxAgeMillis: Long = TRASH_MAX_AGE_MS, now: Long = System.currentTimeMillis()): Int {
        var purged = 0
        trashDir(context).listFiles()?.forEach { f ->
            if (isExpired(f.name, now, maxAgeMillis) && f.delete()) purged++
        }
        return purged
    }

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
