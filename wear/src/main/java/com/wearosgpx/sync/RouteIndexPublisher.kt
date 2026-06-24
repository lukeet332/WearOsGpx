package com.wearosgpx.sync

import android.content.Context
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.wearosgpx.data.gpx.RouteCatalog
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Publishes the watch's current route catalog as a Data Layer item the phone reads
 * to show/inspect/delete routes. Call after any change (import, delete, startup).
 */
object RouteIndexPublisher {

    const val PATH = "/routes_index"
    const val KEY_ROUTES = "routes"
    const val KEY_TIMESTAMP = "ts"

    suspend fun publish(context: Context) {
        val entries = RouteCatalog.routesDir(context).listFiles()
            ?.filter { it.isFile && it.extension.equals("gpx", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?.mapNotNull { file ->
                runCatching {
                    file.inputStream().use { com.wearosgpx.data.gpx.GpxParser.parse(it) }
                }.getOrNull()?.let { route ->
                    RouteIndexEntry(
                        fileName = file.name,
                        name = route.name ?: file.nameWithoutExtension,
                        distanceMeters = route.totalDistanceMeters,
                        ascentMeters = route.totalAscentMeters,
                        pointCount = route.points.size,
                    )
                }
            }
            ?: emptyList()

        val json = Json.encodeToString(RouteIndex(entries))
        val request = PutDataMapRequest.create(PATH).apply {
            dataMap.putString(KEY_ROUTES, json)
            dataMap.putLong(KEY_TIMESTAMP, System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()

        Wearable.getDataClient(context).putDataItem(request).await()
    }
}
