package com.wearos.ancsbridge.health

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.wearos.ancsbridge.ble.BleConnectionManager
import com.wearos.ancsbridge.ble.BleConnectionManager.HealthWriteResult
import com.wearos.ancsbridge.model.ConnectionState
import kotlinx.coroutines.CancellationException
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
        /** Next flush while a backlog is left, so it drains in small bursts rather than one long one. */
        private const val BACKLOG_FLUSH_MS = 2 * 60_000L
        /** After the iPhone refused a batch (e.g. Apple Health sync off), only retry on reconnect or after this. */
        private const val FAILURE_BACKOFF_MS = 30 * 60_000L

        /** How long a flush waits for [canWrite] before giving up until the next one. */
        private const val WRITABLE_WAIT_MS = 10_000L
        private const val WRITABLE_POLL_MS = 500L

        private const val BUSY_RETRY_DELAY_MS = 250L
        private const val MAX_BUSY_ATTEMPTS = 8
        private const val PAUSE_BETWEEN_WRITES_MS = 50L
        private const val MAX_WRITES_PER_FLUSH = 25

        /** Writes per queue read/ack, so the queue file is rewritten less often. */
        private const val WRITES_PER_ACK = 20
    }

    private val flushLock = Mutex()
    private var job: Job? = null

    /** When the current connection came up, or 0 while disconnected. */
    @Volatile private var connectedSinceMs = 0L
    @Volatile private var backlogLeft = false
    @Volatile private var backoffUntilMs = 0L

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
                            backoffUntilMs = 0L // a new connection deserves a fresh try
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
                    delay(if (backlogLeft) BACKLOG_FLUSH_MS else PERIODIC_FLUSH_MS)
                    flush(if (backlogLeft) "backlog" else "periodic")
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
            flushLocked(reason)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The queue file is unreadable or the disk is full: skip this round
            // rather than crash the service that also runs ANCS.
            Log.e(TAG, "Health flush failed", e)
        } finally {
            flushLock.unlock()
        }
    }

    private suspend fun flushLocked(reason: String) {
        // Not gated on Health Sync being on: queued records were accepted (relay batches
        // keep coming in, and losing a permission turns Health Sync off by itself);
        // turning Health Sync off from the watch clears the queue.
        if (!isSettled()) return
        if (SystemClock.elapsedRealtime() < backoffUntilMs) return
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
                // A record corrupted on disk would make encodeBatch throw on every flush.
                val batch = chunk.records.subList(confirmed, end).filter(HealthProtocol::isValid)
                if (batch.isEmpty()) {
                    confirmed = end
                    continue
                }
                when (writeWithRetry(HealthProtocol.encodeBatch(batch))) {
                    HealthWriteResult.SUCCESS -> {
                        confirmed = end
                        writes++
                        delay(PAUSE_BETWEEN_WRITES_MS)
                    }
                    HealthWriteResult.UNSUPPORTED -> {
                        Log.d(TAG, "Companion app not reachable; keeping queued records")
                        stop = true
                    }
                    HealthWriteResult.FAILED -> {
                        Log.w(TAG, "Health write refused or timed out; backing off")
                        backoffUntilMs = SystemClock.elapsedRealtime() + FAILURE_BACKOFF_MS
                        stop = true
                    }
                    HealthWriteResult.BUSY, HealthWriteResult.TIMEOUT -> {
                        Log.w(TAG, "Health write not confirmed yet; will retry later")
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
        val left = withContext(Dispatchers.IO) { store.size() }
        backlogLeft = sent > 0 && left > 0
        if (sent > 0) {
            Log.i(TAG, "Sent $sent health records in $writes writes ($reason), $left left")
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
