package com.wearosgpx.mobile.sync

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.wearosgpx.mobile.health.HealthConnectWriter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

/**
 * Receives finished runs from the watch over the Data Layer and writes them to
 * Health Connect. The watch sends gzipped JSON as an Asset under "/run/...".
 */
class RunSyncListenerService : WearableListenerService() {

    private val json = Json { ignoreUnknownKeys = true }

    override fun onDataChanged(events: DataEventBuffer) {
        val writer = HealthConnectWriter(this)
        val runItems = events.filter {
            it.type == DataEvent.TYPE_CHANGED &&
                it.dataItem.uri.path?.startsWith("/run") == true
        }
        for (event in runItems) {
            val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
            val asset = dataMap.getAsset("payload") ?: continue
            runCatching {
                runBlocking {
                    val bytes = readAsset(asset)
                    val payload = json.decodeFromString<RunPayload>(ungzip(bytes).toString(Charsets.UTF_8))
                    if (!writer.isAvailable()) {
                        Log.w(TAG, "Health Connect not available; dropping run.")
                        return@runBlocking
                    }
                    if (!writer.hasAllPermissions()) {
                        Log.w(TAG, "Health Connect permissions not granted; open the phone app to grant.")
                        return@runBlocking
                    }
                    writer.write(payload)
                    Log.i(TAG, "Wrote run (${payload.points.size} pts) to Health Connect.")
                }
            }.onFailure { Log.e(TAG, "Failed to import run", it) }
        }
    }

    private suspend fun readAsset(asset: com.google.android.gms.wearable.Asset): ByteArray {
        val response = Wearable.getDataClient(this).getFdForAsset(asset).await()
        return response.inputStream.use { it.readBytes() }
    }

    private fun ungzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPInputStream(data.inputStream()).use { it.copyTo(out) }
        return out.toByteArray()
    }

    companion object {
        private const val TAG = "RunSyncListener"
    }
}
