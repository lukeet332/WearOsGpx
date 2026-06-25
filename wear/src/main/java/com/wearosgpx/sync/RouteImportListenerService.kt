package com.wearosgpx.sync

import android.util.Log
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.wearosgpx.data.gpx.BaseMapStore
import com.wearosgpx.data.gpx.RouteCatalog
import com.wearosgpx.data.gpx.RouteUpdates
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import java.io.File

/** Receives GPX routes (and their baked basemaps) pushed from the phone. */
class RouteImportListenerService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val path = event.dataItem.uri.path ?: continue
            when {
                path.startsWith(PATH_BASEMAP) -> handleBaseMap(event)
                path.startsWith(PATH) -> handleRoute(event)
            }
        }
    }

    private fun handleRoute(event: DataEvent) {
        val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
        val asset = dataMap.getAsset(KEY_GPX) ?: return
        val name = dataMap.getString(KEY_NAME) ?: "route-${dataMap.getLong(KEY_TIMESTAMP)}.gpx"
        runCatching {
            runBlocking {
                val bytes = readAsset(asset)
                val file = File(RouteCatalog.routesDir(this@RouteImportListenerService), safeName(name))
                file.writeBytes(bytes)
                Log.i(TAG, "Imported route '${file.name}' (${bytes.size} bytes).")
                RouteUpdates.bump()
                RouteIndexPublisher.publish(this@RouteImportListenerService)
            }
        }.onFailure { Log.e(TAG, "Route import failed", it) }
    }

    private fun handleBaseMap(event: DataEvent) {
        val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
        val asset = dataMap.getAsset(KEY_MAP) ?: return
        val route = dataMap.getString(KEY_ROUTE) ?: return
        runCatching {
            runBlocking {
                val bytes = readAsset(asset)
                BaseMapStore.save(this@RouteImportListenerService, safeName(route), bytes)
                Log.i(TAG, "Saved basemap for '$route' (${bytes.size} bytes).")
                RouteUpdates.bump()
            }
        }.onFailure { Log.e(TAG, "Basemap save failed", it) }
    }

    /** Phone → watch delete request: payload is the file name to remove. */
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != PATH_DELETE) return
        val fileName = String(event.data, Charsets.UTF_8)
        runCatching {
            runBlocking {
                val removed = RouteCatalog.deleteRoute(this@RouteImportListenerService, fileName)
                BaseMapStore.delete(this@RouteImportListenerService, safeName(fileName))
                Log.i(TAG, "Delete '$fileName' → $removed")
                RouteUpdates.bump()
                RouteIndexPublisher.publish(this@RouteImportListenerService)
            }
        }.onFailure { Log.e(TAG, "Route delete failed", it) }
    }

    private suspend fun readAsset(asset: Asset): ByteArray =
        Wearable.getDataClient(this).getFdForAsset(asset).await()
            .inputStream.use { it.readBytes() }

    /** Keep a filesystem-safe name and force a .gpx extension. */
    private fun safeName(name: String): String {
        val cleaned = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (cleaned.endsWith(".gpx", ignoreCase = true)) cleaned else "$cleaned.gpx"
    }

    companion object {
        private const val TAG = "RouteImport"
        const val PATH = "/route"
        const val PATH_BASEMAP = "/basemap"
        const val PATH_DELETE = "/route/delete"
        const val KEY_GPX = "gpx"
        const val KEY_NAME = "name"
        const val KEY_TIMESTAMP = "ts"
        const val KEY_MAP = "map"
        const val KEY_ROUTE = "route"
    }
}
