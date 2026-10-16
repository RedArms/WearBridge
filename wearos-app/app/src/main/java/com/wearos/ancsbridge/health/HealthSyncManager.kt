package com.wearos.ancsbridge.health

import android.content.Context
import android.os.SystemClock
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
 * Runs inside AncsService. It flushes shortly after connecting, when a full
 * write's worth of new data is queued, and periodically as a safety net, so the
 * link sees few writes (every GATT write can make the watch vibrate, gotcha 10).
 * Nothing is written during the first seconds of a connection, while [canWrite]
 * says no (a call is ringing, ANCS is busy), or while another flush runs.
 *
 * Records leave the queue only once the iPhone acknowledged them, and the iPhone
 * deduplicates by sync identifier, so resending a batch whose acknowledgement
 * got lost is harmless.
 */
class HealthSyncManager(
    private val context: Context,
    private val connection: BleConnectionManager,
    private val scope: CoroutineScope,
    private val canWrite: () -> Boolean = { true }
) {

    companion object {
        private const val TAG = "HealthSyncManager"

        /** Let the ANCS subscription and companion reads finish before writing. */
        private const val SETTLE_AFTER_CONNECT_MS = 10_000L

        /** Group passive batches; only flush early once a full write is queued. */
        private const val NEW_DATA_DEBOUNCE_MS = 60_000L
        private const val PERIODIC_FLUSH_MS = 15 * 60_000L

        /** How long a flush waits for [canWrite] before giving up until the next one. */
        private const val WRITABLE_WAIT_MS = 10_000L
        private const val WRITABLE_POLL_MS = 500L

        private const val BUSY_RETRY_DELAY_MS = 250L
        private const val MAX_BUSY_ATTEMPTS = 8
        private const val PAUSE_BETWEEN_WRITES_MS = 50L
        private const val MAX_WRITES_PER_FLUSH = 500

        /** Writes per queue read/ack, so the queue file is rewritten less often. */
        private const val WRITES_PER_ACK = 20
    }

    private val flushLock = Mutex()
    private var job: Job? = null

    /** When the current connection came up, or 0 while disconnected. */
    @Volatile private var connectedSinceMs = 0L

    fun start() {
        if (job != null) return
        job = scope.launch {
            launch {
                connection.connectionState
                    .map { it is ConnectionState.Connected }
                    .distinctUntilChanged()
                    .collectLatest { connected ->
                        if (connected) {
                            connectedSinceMs = SystemClock.elapsedRealtime()
                            delay(SETTLE_AFTER_CONNECT_MS)
                            flush("connected")
                        } else {
                            connectedSinceMs = 0L
                        }
                    }
            }
            launch {
                HealthMonitoring.newData.collect {
                    delay(NEW_DATA_DEBOUNCE_MS)
                    val perWrite = HealthProtocol.maxRecordsPerWrite(connection.negotiatedMtu)
                    val pending = withContext(Dispatchers.IO) { HealthMonitoring.store(context).size() }
                    if (pending >= perWrite) flush("new data")
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

    private fun isSettled(): Boolean {
        val since = connectedSinceMs
        return since != 0L &&
            connection.connectionState.value is ConnectionState.Connected &&
            SystemClock.elapsedRealtime() - since >= SETTLE_AFTER_CONNECT_MS
    }

    /** Waits a little for [canWrite]; false if the link is gone or stays busy. */
    private suspend fun awaitWritable(): Boolean {
        var waited = 0L
        while (isSettled()) {
            if (canWrite()) return true
            if (waited >= WRITABLE_WAIT_MS) return false
            delay(WRITABLE_POLL_MS)
            waited += WRITABLE_POLL_MS
        }
        return false
    }

    private suspend fun flush(reason: String) {
        if (!flushLock.tryLock()) return // a flush is already running
        try {
            if (!HealthMonitoring.isEnabled(context) || !isSettled()) return
            val store = HealthMonitoring.store(context)
            var sent = 0
            var writes = 0
            var stop = false
            while (!stop && writes < MAX_WRITES_PER_FLUSH && isSettled()) {
                val perWrite = HealthProtocol.maxRecordsPerWrite(connection.negotiatedMtu)
                val chunk = withContext(Dispatchers.IO) { store.peek(perWrite * WRITES_PER_ACK) }
                if (chunk.records.isEmpty()) break

                var confirmed = 0
                while (confirmed < chunk.records.size) {
                    if (writes >= MAX_WRITES_PER_FLUSH || !awaitWritable()) {
                        stop = true
                        break
                    }
                    val end = minOf(confirmed + perWrite, chunk.records.size)
                    val payload = HealthProtocol.encodeBatch(chunk.records.subList(confirmed, end))
                    when (writeWithRetry(payload)) {
                        HealthWriteResult.SUCCESS -> {
                            confirmed = end
                            writes++
                            delay(PAUSE_BETWEEN_WRITES_MS)
                        }
                        HealthWriteResult.UNSUPPORTED -> {
                            Log.d(TAG, "Companion app not reachable; keeping queued records")
                            stop = true
                        }
                        HealthWriteResult.BUSY, HealthWriteResult.FAILED -> {
                            Log.w(TAG, "Health write not acknowledged; will retry later")
                            stop = true
                        }
                    }
                    if (stop) break
                }
                if (confirmed > 0) {
                    withContext(Dispatchers.IO) { store.ack(chunk, confirmed) }
                    sent += confirmed
                }
            }
            if (sent > 0) {
                val left = withContext(Dispatchers.IO) { store.size() }
                Log.i(TAG, "Sent $sent health records in $writes writes ($reason), $left left")
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
