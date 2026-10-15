package com.wearos.ancsbridge.health

import android.content.Context
import android.util.Log
import com.wearos.ancsbridge.ble.BleConnectionManager
import com.wearos.ancsbridge.ble.BleConnectionManager.HealthWriteResult
import com.wearos.ancsbridge.model.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Sends queued health records to the iPhone companion app over BLE.
 *
 * Runs inside AncsService. It flushes shortly after connecting, a little after
 * new data arrives (so passive batches are grouped), and periodically as a
 * safety net. Records leave the queue only once the iPhone acknowledged them,
 * and the iPhone deduplicates by sync identifier, so resending a batch whose
 * acknowledgement got lost is harmless.
 */
class HealthSyncManager(
    private val context: Context,
    private val connection: BleConnectionManager,
    private val scope: CoroutineScope
) {

    companion object {
        private const val TAG = "HealthSyncManager"

        /** Let the ANCS subscription and companion reads finish before writing. */
        private const val SETTLE_AFTER_CONNECT_MS = 10_000L

        /** Group passive batches that arrive close together. */
        private const val NEW_DATA_DEBOUNCE_MS = 30_000L
        private const val PERIODIC_FLUSH_MS = 15 * 60_000L

        private const val BUSY_RETRY_DELAY_MS = 250L
        private const val MAX_BUSY_ATTEMPTS = 8
        private const val PAUSE_BETWEEN_WRITES_MS = 50L
        private const val MAX_BATCHES_PER_FLUSH = 500
    }

    private val flushLock = Mutex()
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            launch {
                connection.connectionState
                    .map { it is ConnectionState.Connected }
                    .distinctUntilChanged()
                    .collectLatest { connected ->
                        if (connected) {
                            delay(SETTLE_AFTER_CONNECT_MS)
                            flush("connected")
                        }
                    }
            }
            launch {
                HealthMonitoring.newData.collect {
                    delay(NEW_DATA_DEBOUNCE_MS)
                    flush("new data")
                }
            }
            launch {
                while (isActive) {
                    delay(PERIODIC_FLUSH_MS)
                    flush("periodic")
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun flush(reason: String) {
        if (!flushLock.tryLock()) return // a flush is already running
        try {
            if (!HealthMonitoring.isEnabled(context)) return
            val store = HealthMonitoring.store(context)
            var sent = 0
            var batches = 0
            while (batches < MAX_BATCHES_PER_FLUSH &&
                connection.connectionState.value is ConnectionState.Connected
            ) {
                val maxRecords = HealthProtocol.maxRecordsPerWrite(connection.negotiatedMtu)
                val batch = withContext(Dispatchers.IO) { store.peek(maxRecords) }
                if (batch.records.isEmpty()) break

                when (writeWithRetry(HealthProtocol.encodeBatch(batch.records))) {
                    HealthWriteResult.SUCCESS -> {
                        withContext(Dispatchers.IO) { store.ack(batch) }
                        sent += batch.records.size
                        batches++
                        delay(PAUSE_BETWEEN_WRITES_MS)
                    }
                    HealthWriteResult.UNSUPPORTED -> {
                        Log.d(TAG, "Companion app not reachable; keeping queued records")
                        break
                    }
                    HealthWriteResult.BUSY, HealthWriteResult.FAILED -> {
                        Log.w(TAG, "Health write not acknowledged; will retry later")
                        break
                    }
                }
            }
            if (sent > 0) {
                val left = withContext(Dispatchers.IO) { store.size() }
                Log.i(TAG, "Sent $sent health records ($reason), $left left")
            }
        } finally {
            flushLock.unlock()
        }
    }

    private suspend fun writeWithRetry(payload: ByteArray): HealthWriteResult {
        repeat(MAX_BUSY_ATTEMPTS) {
            val result = connection.writeHealthData(payload)
            if (result != HealthWriteResult.BUSY) return result
            delay(BUSY_RETRY_DELAY_MS)
        }
        return HealthWriteResult.BUSY
    }
}
