package com.wearos.ancsbridge.relay.core

import com.wearos.ancsbridge.health.HealthRecord
import java.io.File

/** How far back each relay run reads Samsung Health. */
object SyncWindow {

    /**
     * Samsung Health keeps refining recent data (sleep is processed after waking,
     * the watch syncs late), so every run re-reads this much before the last success.
     */
    const val OVERLAP_SEC = 36 * 3600L

    /** The first run backfills this much history. */
    const val FIRST_RUN_LOOKBACK_SEC = 30 * 24 * 3600L

    /** Data the watch syncs late (it was away from this phone) is looked for this far back at most. */
    const val MAX_LATE_SYNC_SEC = 10 * 24 * 3600L

    /** Re-read from this long before the newest record already seen. */
    const val NEWEST_RECORD_MARGIN_SEC = 3600L

    /**
     * From [OVERLAP_SEC] before the last success, or from just before the newest
     * record seen so far when that's earlier: while the watch is away from this
     * phone nothing new reaches Samsung Health, then the watch syncs the whole
     * absence at once, which can start long before the last (empty) success.
     */
    fun startEpochSec(nowEpochSec: Long, lastSuccessEpochSec: Long?, newestRecordEndEpochSec: Long? = null): Long {
        if (lastSuccessEpochSec == null) return nowEpochSec - FIRST_RUN_LOOKBACK_SEC
        val start = minOf(nowEpochSec, lastSuccessEpochSec) - OVERLAP_SEC
        if (newestRecordEndEpochSec == null) return start
        val sinceNewest = maxOf(newestRecordEndEpochSec - NEWEST_RECORD_MARGIN_SEC, nowEpochSec - MAX_LATE_SYNC_SEC)
        return minOf(start, sinceNewest)
    }
}

/**
 * Remembers which records were sent recently, so overlapping reads don't resend
 * them. Entries outlive the longest read window ([SyncWindow.MAX_LATE_SYNC_SEC]),
 * so a record is sent once per path (the iPhone deduplicates by sync identifier
 * anyway, but every resend costs mailbox messages).
 */
class SentLog(private val file: File, private val ttlSec: Long = DEFAULT_TTL_SEC) {

    companion object {
        const val DEFAULT_TTL_SEC = SyncWindow.MAX_LATE_SYNC_SEC + 2 * 24 * 3600L

        fun fingerprint(record: HealthRecord): String =
            "${record.type}:${record.startEpochSec}:${record.endEpochSec}:${record.value}"
    }

    @Synchronized
    fun filterUnsent(records: List<HealthRecord>, nowEpochSec: Long): List<HealthRecord> {
        val sent = load(nowEpochSec)
        return records.distinct().filter { fingerprint(it) !in sent }
    }

    @Synchronized
    fun markSent(records: List<HealthRecord>, nowEpochSec: Long) {
        val sent = load(nowEpochSec)
        if (records.isEmpty() && file.exists()) return
        records.forEach { sent[fingerprint(it)] = nowEpochSec }
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(sent.entries.joinToString("\n") { "${it.key} ${it.value}" })
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    /** Fingerprint → time sent, without expired entries. */
    private fun load(nowEpochSec: Long): MutableMap<String, Long> {
        if (!file.exists()) return mutableMapOf()
        val map = mutableMapOf<String, Long>()
        file.readLines().forEach { line ->
            val separator = line.lastIndexOf(' ')
            if (separator <= 0) return@forEach
            val sentAt = line.substring(separator + 1).toLongOrNull() ?: return@forEach
            if (nowEpochSec - sentAt < ttlSec) map[line.substring(0, separator)] = sentAt
        }
        return map
    }
}
