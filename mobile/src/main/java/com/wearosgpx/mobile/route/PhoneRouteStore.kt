package com.wearosgpx.mobile.route

import android.content.Context
import java.io.File

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
}
