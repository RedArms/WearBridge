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

    fun startEpochSec(nowEpochSec: Long, lastSuccessEpochSec: Long?): Long =
        if (lastSuccessEpochSec == null) {
            nowEpochSec - FIRST_RUN_LOOKBACK_SEC
        } else {
            minOf(nowEpochSec, lastSuccessEpochSec) - OVERLAP_SEC
        }
}

/**
 * Remembers which records were sent recently, so overlapping reads don't resend
 * them every hour. Entries expire after [ttlSec]: a record still inside the read
 * window is sent once more after that, which repairs any batch lost on the way
 * (the iPhone deduplicates by sync identifier).
 */
class SentLog(private val file: File, private val ttlSec: Long = DEFAULT_TTL_SEC) {

    companion object {
        const val DEFAULT_TTL_SEC = 24 * 3600L

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
        records.forEach { sent[fingerprint(it)] = nowEpochSec }
        file.parentFile?.mkdirs()
        file.writeText(sent.entries.joinToString("\n") { "${it.key} ${it.value}" })
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
