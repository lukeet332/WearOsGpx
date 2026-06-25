package com.wearosgpx.mobile.sync

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

/**
 * Receives finished runs from the watch over the Data Layer and hands them to
 * [RunImporter] (which writes to Health Connect, de-duplicated). The watch sends
 * gzipped JSON as an Asset under "/run/...".
 */
class RunSyncListenerService : WearableListenerService() {

    private val json = Json { ignoreUnknownKeys = true }

    override fun onDataChanged(events: DataEventBuffer) {
        val runItems = events.filter {
            it.type == DataEvent.TYPE_CHANGED &&
                it.dataItem.uri.path?.startsWith("/run") == true
        }
        Log.i(TAG, "onDataChanged: ${runItems.size} /run event(s)")
        for (event in runItems) {
            val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
            val asset = dataMap.getAsset("payload") ?: continue
            runCatching {
                runBlocking {
                    val bytes = readAsset(asset)
                    val payload = json.decodeFromString<RunPayload>(ungzip(bytes).toString(Charsets.UTF_8))
                    RunImporter.importPayload(this@RunSyncListenerService, payload, "listener")
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
