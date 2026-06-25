package com.wearosgpx.data.gpx

import android.content.Context
import java.io.File

/**
 * Source of selectable routes: GPX files imported from the phone (or `adb push`ed),
 * stored in [routesDir]. No bundled demo routes.
 */
object RouteCatalog {

    /**
     * Where imported .gpx files live. Using the external files dir means it's also
     * reachable by `adb push` for testing:
     *   adb push my.gpx /sdcard/Android/data/com.wearosgpx/files/routes/
     */
    fun routesDir(context: Context): File {
        val dir = context.getExternalFilesDir("routes") ?: File(context.filesDir, "routes")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Deletes an imported route file by name. Returns true if it was removed. */
    fun deleteRoute(context: Context, fileName: String): Boolean {
        val file = File(routesDir(context), fileName)
        return file.exists() && file.isFile && file.delete()
    }

    fun loadAll(context: Context): List<GpxRoute> =
        routesDir(context).listFiles()
            ?.filter { it.isFile && it.extension.equals("gpx", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?.mapNotNull { file ->
                runCatching { file.inputStream().use { GpxParser.parse(it) } }.getOrNull()
            }
            ?: emptyList()
}
