package com.wearos.ancsbridge.health

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Durable FIFO of health records waiting to be sent to the iPhone.
 *
 * Records are appended as fixed-size [HealthProtocol.RECORD_SIZE] entries to a
 * single file, so a crash mid-write leaves at most one partial record at the
 * tail, which is dropped on the next write. The queue is capped at [maxRecords];
 * the oldest records go first when the watch has been away from the iPhone for
 * a long time.
 *
 * Usage: [peek] a batch, send it, then [ack] it. Records appended in between
 * are kept, and an ack after the cap trimmed the head only removes what is left
 * of the batch.
 */
class HealthRecordStore(
    private val file: File,
    private val maxRecords: Int = DEFAULT_MAX_RECORDS
) {

    companion object {
        /** About 850 KB on disk; weeks of passive data. */
        const val DEFAULT_MAX_RECORDS = 50_000
    }

    /** A peeked batch. [headIndex] is the absolute position of its first record. */
    class Batch internal constructor(val records: List<HealthRecord>, internal val headIndex: Long)

    /** Records removed from the head since this store was created (acks and trims). */
    private var removedFromHead = 0L

    @Synchronized
    fun append(records: List<HealthRecord>) {
        val valid = records.filter(HealthProtocol::isValid)
        if (valid.isEmpty()) return
        file.parentFile?.mkdirs()
        dropPartialTail()
        FileOutputStream(file, true).use { it.write(HealthProtocol.encodeRecords(valid)) }
        val overflow = size() - maxRecords
        if (overflow > 0) removeFirst(overflow)
    }

    @Synchronized
    fun size(): Int = (file.length() / HealthProtocol.RECORD_SIZE).toInt()

    @Synchronized
    fun peek(maxCount: Int): Batch {
        val count = minOf(maxCount, size())
        if (count <= 0) return Batch(emptyList(), removedFromHead)
        val bytes = ByteArray(count * HealthProtocol.RECORD_SIZE)
        RandomAccessFile(file, "r").use { it.readFully(bytes) }
        return Batch(HealthProtocol.decodeRecords(bytes, 0, count), removedFromHead)
    }

    /** Remove a batch that the iPhone confirmed. */
    @Synchronized
    fun ack(batch: Batch) {
        val alreadyTrimmed = (removedFromHead - batch.headIndex).coerceAtLeast(0L)
        val remaining = batch.records.size - alreadyTrimmed
        if (remaining > 0) removeFirst(remaining.toInt())
    }

    @Synchronized
    fun clear() {
        removedFromHead += size()
        file.delete()
    }

    private fun removeFirst(count: Int) {
        val total = size()
        val toRemove = minOf(count, total)
        if (toRemove <= 0) return
        removedFromHead += toRemove
        if (toRemove == total) {
            file.delete()
            return
        }
        val keepBytes = ByteArray((total - toRemove) * HealthProtocol.RECORD_SIZE)
        RandomAccessFile(file, "r").use {
            it.seek(toRemove.toLong() * HealthProtocol.RECORD_SIZE)
            it.readFully(keepBytes)
        }
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(keepBytes)
        if (!tmp.renameTo(file)) {
            file.writeBytes(keepBytes)
            tmp.delete()
        }
    }

    private fun dropPartialTail() {
        if (!file.exists()) return
        val partial = file.length() % HealthProtocol.RECORD_SIZE
        if (partial != 0L) {
            RandomAccessFile(file, "rw").use { it.setLength(file.length() - partial) }
        }
    }
}
