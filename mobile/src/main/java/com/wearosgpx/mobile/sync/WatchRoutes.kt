package com.wearosgpx.mobile.sync

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json

/**
 * Reads the watch's published route index and sends route-delete requests back.
 * Paths mirror the watch: index DataItem at "/routes_index", delete message at
 * "/route/delete".
 */
object WatchRoutes {

    private const val INDEX_PATH = "/routes_index"
    private const val DELETE_PATH = "/route/delete"
    private val json = Json { ignoreUnknownKeys = true }

    /** Fetches the current route index from the connected watch (empty if none). */
    suspend fun fetchIndex(context: Context): List<RouteIndexEntry> {
        val buffer = Wearable.getDataClient(context)
            .getDataItems(Uri.parse("wear://*$INDEX_PATH")).await()
        return try {
            buffer.firstOrNull()?.let { item ->
                val routesJson = DataMapItem.fromDataItem(item).dataMap.getString("routes")
                routesJson?.let { json.decodeFromString<RouteIndex>(it).routes }
            } ?: emptyList()
        } finally {
            buffer.release()
        }
    }

    /** Pushes a GPX route to the watch (it saves it and republishes its index). */
    suspend fun sendRoute(context: Context, name: String, bytes: ByteArray) {
        val request = PutDataMapRequest.create("/route/${System.currentTimeMillis()}").apply {
            dataMap.putAsset("gpx", Asset.createFromBytes(bytes))
            dataMap.putString("name", name)
            dataMap.putLong("ts", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(request).await()
    }

    /** Pushes the baked vector basemap for a route (saved on the watch as <route>.map). */
    suspend fun sendBaseMap(context: Context, gpxFileName: String, bytes: ByteArray) {
        val request = PutDataMapRequest.create("/basemap/${System.currentTimeMillis()}").apply {
            dataMap.putAsset("map", Asset.createFromBytes(bytes))
            dataMap.putString("route", gpxFileName)
            dataMap.putLong("ts", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(request).await()
    }

    /** Asks the watch to delete an imported route by file name. */
    suspend fun delete(context: Context, fileName: String) {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        val messageClient = Wearable.getMessageClient(context)
        for (node in nodes) {
            messageClient.sendMessage(node.id, DELETE_PATH, fileName.toByteArray(Charsets.UTF_8)).await()
        }
    }
}
