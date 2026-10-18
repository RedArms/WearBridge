package com.wearos.ancsbridge.relay

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.wearable.Wearable
import com.wearos.ancsbridge.health.HealthProtocol
import com.wearos.ancsbridge.health.HealthRecord
import com.wearos.ancsbridge.relay.core.SentLog
import com.wearos.ancsbridge.relay.core.SyncWindow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Last run results, shown in [RelayActivity]. */
class RelayState(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("relay", Context.MODE_PRIVATE)

    val sentLog = SentLog(File(context.applicationContext.filesDir, "relay_sent.txt"))

    val lastSuccessEpochSec: Long?
        get() = prefs.getLong(KEY_LAST_SUCCESS, -1L).takeIf { it >= 0L }

    val lastMessage: String?
        get() = prefs.getString(KEY_LAST_MESSAGE, null)

    fun recordSuccess(nowEpochSec: Long, sent: Int, read: Int) {
        prefs.edit()
            .putLong(KEY_LAST_SUCCESS, nowEpochSec)
            .putString(KEY_LAST_MESSAGE, "Last sync ${Instant.ofEpochSecond(nowEpochSec)}: $sent new of $read records sent to the watch")
            .apply()
    }

    fun recordFailure(nowEpochSec: Long, message: String) {
        prefs.edit()
            .putString(KEY_LAST_MESSAGE, "Last attempt ${Instant.ofEpochSecond(nowEpochSec)} failed: $message")
            .apply()
    }

    private companion object {
        const val KEY_LAST_SUCCESS = "last_success"
        const val KEY_LAST_MESSAGE = "last_message"
    }
}

/** Sends record batches to the watch over the Wear OS Data Layer. */
object RelaySender {

    class NoWatchException : Exception("The watch is not connected to this phone")

    suspend fun send(context: Context, records: List<HealthRecord>) {
        if (records.isEmpty()) return
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        if (nodes.isEmpty()) throw NoWatchException()
        val messages = Wearable.getMessageClient(context)
        for (chunk in records.chunked(HealthProtocol.MAX_RECORDS_PER_BATCH)) {
            val payload = HealthProtocol.encodeBatch(chunk)
            for (node in nodes) {
                messages.sendMessage(node.id, HealthProtocol.RELAY_MESSAGE_PATH, payload).await()
            }
        }
    }
}

/** Reads Samsung Health and forwards new records to the watch. Runs hourly and on demand. */
class RelayWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "RelayWorker"
        private const val PERIODIC_WORK = "wearbridge-relay"
        const val ONE_TIME_WORK_NAME = "wearbridge-relay-now"

        /** Periodic and on-demand runs share the sent log; one at a time. */
        private val runLock = Mutex()

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RelayWorker>(1, TimeUnit.HOURS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun syncNow(context: Context) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONE_TIME_WORK_NAME, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<RelayWorker>().build())
        }
    }

    override suspend fun doWork(): Result = runLock.withLock {
        val state = RelayState(applicationContext)
        val now = Instant.now()
        try {
            val from = Instant.ofEpochSecond(SyncWindow.startEpochSec(now.epochSecond, state.lastSuccessEpochSec))
            val records = SamsungHealthReader(applicationContext).read(from, now)
            val fresh = state.sentLog.filterUnsent(records, now.epochSecond)
            RelaySender.send(applicationContext, fresh)
            state.sentLog.markSent(fresh, now.epochSecond)
            state.recordSuccess(now.epochSecond, sent = fresh.size, read = records.size)
            Log.i(TAG, "Sent ${fresh.size} of ${records.size} records ($from..$now)")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RelaySender.NoWatchException) {
            // Not Result.retry(): on periodic work its backoff grows to 5 h, so the
            // next regular run comes sooner.
            state.recordFailure(now.epochSecond, e.message ?: "watch not connected")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Relay run failed", e)
            state.recordFailure(now.epochSecond, SamsungHealthReader.explain(e) ?: (e.message ?: e.toString()))
            Result.failure()
        }
    }
}
