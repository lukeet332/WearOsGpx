package com.wearosgpx.sync

import android.content.Context
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Sends a finished run to the phone via the Wearable [com.google.android.gms.wearable.DataClient].
 *
 * DataClient persists the item and delivers it whenever the phone is in range —
 * so a run recorded with no phone present still syncs once you're home. The run
 * is gzipped JSON carried as an [Asset] (track logs are too big for the inline
 * DataMap limit). The start time is in the path so each run is a distinct item.
 */
class RunSyncManager(context: Context) {

    private val dataClient = Wearable.getDataClient(context)

    suspend fun send(payload: RunPayload) {
        val bytes = gzip(Json.encodeToString(payload).toByteArray(Charsets.UTF_8))
        val request = PutDataMapRequest.create("$PATH/${payload.startEpochMillis}").apply {
            dataMap.putAsset(KEY_PAYLOAD, Asset.createFromBytes(bytes))
            dataMap.putLong(KEY_TIMESTAMP, payload.startEpochMillis)
        }.asPutDataRequest().setUrgent()

        dataClient.putDataItem(request).await()
    }

    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    companion object {
        const val PATH = "/run"
        const val KEY_PAYLOAD = "payload"
        const val KEY_TIMESTAMP = "ts"
    }
}
