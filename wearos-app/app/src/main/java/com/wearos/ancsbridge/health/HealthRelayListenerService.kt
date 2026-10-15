package com.wearos.ancsbridge.health

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Receives health batches from the optional phone relay (`:relay`), which reads
 * Samsung Health (sleep stages and score, SpO2, blood pressure, heart rate) on
 * the Android phone the watch is paired with. The batches join the same queue
 * as the watch's own data and reach the iPhone over the existing BLE link.
 */
class HealthRelayListenerService : WearableListenerService() {

    companion object {
        private const val TAG = "HealthRelayListener"
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != HealthProtocol.RELAY_MESSAGE_PATH) return
        val records = HealthProtocol.decodeBatch(messageEvent.data)
        if (records == null) {
            Log.w(TAG, "Ignoring malformed relay batch (${messageEvent.data.size} bytes)")
            return
        }
        Log.i(TAG, "Relay batch: ${records.size} records from ${messageEvent.sourceNodeId}")
        HealthMonitoring.onRelayRecords(this, records)
    }
}
