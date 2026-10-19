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
    private val filesDir = context.applicationContext.filesDir

    /** Records already sent to the watch. */
    val watchSentLog = SentLog(File(filesDir, "relay_sent.txt"))

    private val mailboxSentFile = File(filesDir, "relay_mailbox_sent.txt")

    /** Records already queued for the internet mailbox (separate: either path can fail alone). */
    val mailboxSentLog: SentLog by lazy {
        // First run after the update: start from what the watch path already sent.
        val watchFile = File(filesDir, "relay_sent.txt")
        if (!mailboxSentFile.exists() && watchFile.exists()) {
            try {
                watchFile.copyTo(mailboxSentFile)
            } catch (e: java.io.IOException) {
                // Another RelayState did it at the same moment.
            }
        }
        SentLog(mailboxSentFile)
    }

    /**
     * After a re-pairing, what the old pairing had queued is gone: forget what the mailbox
     * path sent and have the next run read [SyncWindow.FIRST_RUN_LOOKBACK_SEC] back for it.
     */
    fun restartMailboxDelivery(nowEpochSec: Long) {
        mailboxSentFile.writeText("") // empty, not deleted: deleting would copy the watch log back
        prefs.edit().putLong(KEY_MAILBOX_BACKFILL_FROM, nowEpochSec - SyncWindow.FIRST_RUN_LOOKBACK_SEC).commit()
    }

    /** Start of a one-off mailbox backfill still to do (see [restartMailboxDelivery]). */
    val mailboxBackfillFromEpochSec: Long?
        get() = prefs.getLong(KEY_MAILBOX_BACKFILL_FROM, -1L).takeIf { it >= 0L }

    val lastSuccessEpochSec: Long?
        get() = prefs.getLong(KEY_LAST_SUCCESS, -1L).takeIf { it >= 0L }

    /** End of the newest record read so far, which marks when the watch last synced to this phone. */
    val newestRecordEndEpochSec: Long?
        get() = prefs.getLong(KEY_NEWEST_RECORD_END, -1L).takeIf { it >= 0L }

    fun noteRecords(records: List<HealthRecord>, nowEpochSec: Long) {
        val newest = records.maxOfOrNull { minOf(it.endEpochSec, nowEpochSec) } ?: return
        if (newest > (newestRecordEndEpochSec ?: -1L)) prefs.edit().putLong(KEY_NEWEST_RECORD_END, newest).apply()
    }

    val lastMessage: String?
        get() = prefs.getString(KEY_LAST_MESSAGE, null)

    fun recordSuccess(nowEpochSec: Long, summary: String, backfillDone: Boolean = false) {
        prefs.edit()
            .apply { if (backfillDone) remove(KEY_MAILBOX_BACKFILL_FROM) }
            .putLong(KEY_LAST_SUCCESS, nowEpochSec)
            .putString(KEY_LAST_MESSAGE, "Last sync ${Instant.ofEpochSecond(nowEpochSec)}: $summary")
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
        const val KEY_NEWEST_RECORD_END = "newest_record_end"
        const val KEY_MAILBOX_BACKFILL_FROM = "mailbox_backfill_from"
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

/**
 * Reads Samsung Health and forwards new records to the iPhone: through the
 * internet mailbox (RelayMailbox) wherever the iPhone is, and to the watch when
 * it's connected. Runs every 15 minutes and on demand.
 */
class RelayWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "RelayWorker"
        private const val PERIODIC_WORK = "wearbridge-relay"
        const val ONE_TIME_WORK_NAME = "wearbridge-relay-now"

        /** Periodic and on-demand runs (and a mailbox reset) share the logs and the outbox; one at a time. */
        val runLock = Mutex()

        fun schedule(context: Context) {
            // 15 minutes: the sleep reaches the iPhone soon after the watch syncs it in the morning.
            val request = PeriodicWorkRequestBuilder<RelayWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun syncNow(context: Context) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONE_TIME_WORK_NAME, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<RelayWorker>().build())
        }
    }

    override suspend fun doWork(): Result = runLock.withLock {
        val state = RelayState(applicationContext)
        val mailbox = RelayMailbox(applicationContext)
        val now = Instant.now()
        val nowSec = now.epochSecond

        var summary: String? = null
        var tookRecords = false
        var backfilled = false
        val readError: Exception? = try {
            val windowStart = SyncWindow.startEpochSec(nowSec, state.lastSuccessEpochSec, state.newestRecordEndEpochSec)
            // After a re-pairing the mailbox path reads further back once; the watch path doesn't need it.
            val backfillFrom = if (mailbox.enabled) state.mailboxBackfillFromEpochSec else null
            val from = Instant.ofEpochSecond(minOf(windowStart, backfillFrom ?: windowStart))
            val records = SamsungHealthReader(applicationContext).read(from, now)
            state.noteRecords(records, nowSec)

            // Internet path: queued durably, re-posted until the iPhone confirms.
            val forMailbox = if (mailbox.enabled) state.mailboxSentLog.filterUnsent(records, nowSec) else emptyList()
            mailbox.enqueue(forMailbox, nowSec)
            state.mailboxSentLog.markSent(forMailbox, nowSec)
            backfilled = backfillFrom != null

            // Watch path, when it's around (it also tells the watch to leave heart rate to us).
            val forWatch = state.watchSentLog.filterUnsent(records.filter { it.endEpochSec >= windowStart }, nowSec)
            val watch = try {
                RelaySender.send(applicationContext, forWatch)
                state.watchSentLog.markSent(forWatch, nowSec)
                if (forWatch.isEmpty()) "watch: nothing new" else "watch: ${forWatch.size} sent"
            } catch (e: CancellationException) {
                throw e
            } catch (e: RelaySender.NoWatchException) {
                null
            } catch (e: Exception) {
                Log.w(TAG, "Sending to the watch failed", e)
                null
            }

            tookRecords = watch != null || mailbox.enabled
            summary = "${records.size} records read ($from..$now). ${watch ?: "watch: not connected"}. " +
                if (mailbox.enabled) "${forMailbox.size} new for the iPhone." else "Internet relay off."
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e
        }

        // Always: post what's queued and read the iPhone's acks, even if Samsung Health
        // couldn't be read this time (e.g. developer mode switched itself off).
        val internet = mailbox.sync(nowSec)

        when {
            readError != null -> {
                Log.e(TAG, "Relay run failed", readError)
                val reason = SamsungHealthReader.explain(readError) ?: (readError.message ?: readError.toString())
                state.recordFailure(nowSec, "$reason. $internet")
            }
            !tookRecords -> {
                // The window stays where it is, so these records are read again next time.
                // Not Result.retry(): on periodic work its backoff grows to 5 h.
                state.recordFailure(nowSec, "the watch is not connected and the internet relay is off")
            }
            else -> {
                state.recordSuccess(nowSec, "$summary $internet", backfillDone = backfilled)
                Log.i(TAG, "$summary $internet")
            }
        }
        Result.success()
    }
}
