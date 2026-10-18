package com.wearos.ancsbridge.relay.core

import com.wearos.ancsbridge.health.HealthProtocol
import com.wearos.ancsbridge.health.HealthRecord
import com.wearos.ancsbridge.health.MailboxCodec
import java.io.File
import java.util.Properties

/**
 * What the relay still has to get to the iPhone through the mailbox, kept on
 * disk so nothing is lost if the phone restarts or has no internet:
 *
 * - **pending** records, packed into messages once worth a message (so the
 *   mailbox sees a few dozen messages a day, well under ntfy.sh's 250/day);
 * - sealed **messages** waiting for the iPhone's ack. Unacknowledged ones are
 *   posted again every [REPOST_AFTER_SEC] (the mailbox only keeps messages for
 *   12 h), and given up after [GIVE_UP_AFTER_SEC].
 */
class MailboxOutbox(private val dir: File) {

    companion object {
        /** Heart-rate-only records wait this long to be grouped; other types go out at once. */
        const val HEART_RATE_DELAY_SEC = 60 * 60L
        const val REPOST_AFTER_SEC = 6 * 3600L
        const val GIVE_UP_AFTER_SEC = 7 * 24 * 3600L
        /** Posts per run at most (ntfy.sh: bursts of 60 requests). */
        const val MAX_POSTS_PER_RUN = 30

        private const val PENDING_FILE = "mailbox_pending.bin"
        private const val MESSAGES_FILE = "mailbox_messages.txt"
        private const val STATE_FILE = "mailbox_state.properties"
        private const val KEY_NEXT_SEQ = "next_seq"
        private const val KEY_PENDING_SINCE = "pending_since"
        private const val KEY_LAST_ACK_ID = "last_ack_id"
    }

    /** A sealed message. [lastPostedSec] is 0 until it has been posted. */
    data class Entry(val seq: Long, val createdSec: Long, val lastPostedSec: Long, val text: String)

    private val pendingFile = File(dir, PENDING_FILE)
    private val messagesFile = File(dir, MESSAGES_FILE)
    private val stateFile = File(dir, STATE_FILE)

    @Synchronized
    fun add(records: List<HealthRecord>, nowSec: Long) {
        val valid = records.filter(HealthProtocol::isValid)
        if (valid.isEmpty()) return
        val pending = loadPending()
        if (pending.isEmpty()) updateState { it.setProperty(KEY_PENDING_SINCE, nowSec.toString()) }
        savePending(pending + valid)
    }

    @Synchronized
    fun pendingCount(): Int = loadPending().size

    /** Seals pending records into messages when they're worth sending. Returns the number of new messages. */
    @Synchronized
    fun pack(key: ByteArray, nowSec: Long): Int {
        val pending = loadPending()
        if (pending.isEmpty()) return 0
        val state = loadState()
        val pendingSince = state.getProperty(KEY_PENDING_SINCE)?.toLongOrNull() ?: nowSec
        val urgent = pending.any { it.type != HealthProtocol.TYPE_HEART_RATE }
        val full = pending.size >= MailboxCodec.MAX_RECORDS_PER_MESSAGE
        if (!urgent && !full && nowSec - pendingSince < HEART_RATE_DELAY_SEC) return 0

        var nextSeq = state.getProperty(KEY_NEXT_SEQ)?.toLongOrNull() ?: 0L
        val created = pending.chunked(MailboxCodec.MAX_RECORDS_PER_MESSAGE).map { chunk ->
            Entry(nextSeq, nowSec, 0L, MailboxCodec.sealBatch(key, nextSeq, chunk)).also {
                nextSeq = (nextSeq + 1) and 0xFFFF_FFFFL
            }
        }
        saveMessages(loadMessages() + created)
        updateState {
            it.setProperty(KEY_NEXT_SEQ, nextSeq.toString())
            it.remove(KEY_PENDING_SINCE)
        }
        savePending(emptyList())
        return created.size
    }

    /** Messages to post now: never posted, or not acknowledged since [REPOST_AFTER_SEC]. */
    @Synchronized
    fun due(nowSec: Long): List<Entry> =
        loadMessages()
            .filter { it.lastPostedSec == 0L || nowSec - it.lastPostedSec >= REPOST_AFTER_SEC }
            .take(MAX_POSTS_PER_RUN)

    @Synchronized
    fun markPosted(seq: Long, nowSec: Long) {
        saveMessages(loadMessages().map { if (it.seq == seq) it.copy(lastPostedSec = nowSec) else it })
    }

    /** The iPhone confirmed these messages. */
    @Synchronized
    fun acknowledge(seqs: Collection<Long>) {
        if (seqs.isEmpty()) return
        val acked = seqs.toSet()
        saveMessages(loadMessages().filter { it.seq !in acked })
    }

    /** Drops messages the iPhone never confirmed within [GIVE_UP_AFTER_SEC]. Returns how many. */
    @Synchronized
    fun dropExpired(nowSec: Long): Int {
        val messages = loadMessages()
        val kept = messages.filter { nowSec - it.createdSec < GIVE_UP_AFTER_SEC }
        if (kept.size != messages.size) saveMessages(kept)
        return messages.size - kept.size
    }

    @Synchronized
    fun waitingForAck(): Int = loadMessages().size

    /** The last ack message read from the mailbox, to poll only newer ones. */
    var lastAckId: String?
        @Synchronized get() = loadState().getProperty(KEY_LAST_ACK_ID)
        @Synchronized set(value) = updateState {
            if (value == null) it.remove(KEY_LAST_ACK_ID) else it.setProperty(KEY_LAST_ACK_ID, value)
        }

    /** Forgets everything, e.g. after re-pairing with a new key. */
    @Synchronized
    fun clear() {
        pendingFile.delete()
        messagesFile.delete()
        val next = loadState().getProperty(KEY_NEXT_SEQ)
        stateFile.delete()
        if (next != null) updateState { it.setProperty(KEY_NEXT_SEQ, next) }
    }

    // --- Storage ---

    private fun loadPending(): List<HealthRecord> {
        if (!pendingFile.exists()) return emptyList()
        val bytes = pendingFile.readBytes()
        return HealthProtocol.decodeRecords(bytes, 0, bytes.size / HealthProtocol.RECORD_SIZE)
    }

    private fun savePending(records: List<HealthRecord>) {
        if (records.isEmpty()) {
            pendingFile.delete()
            return
        }
        writeAtomically(pendingFile, HealthProtocol.encodeRecords(records))
    }

    private fun loadMessages(): List<Entry> {
        if (!messagesFile.exists()) return emptyList()
        return messagesFile.readLines().mapNotNull { line ->
            val parts = line.split(' ')
            if (parts.size != 4) return@mapNotNull null
            Entry(
                seq = parts[0].toLongOrNull() ?: return@mapNotNull null,
                createdSec = parts[1].toLongOrNull() ?: return@mapNotNull null,
                lastPostedSec = parts[2].toLongOrNull() ?: return@mapNotNull null,
                text = parts[3]
            )
        }
    }

    private fun saveMessages(entries: List<Entry>) {
        if (entries.isEmpty()) {
            messagesFile.delete()
            return
        }
        val text = entries.joinToString("\n") { "${it.seq} ${it.createdSec} ${it.lastPostedSec} ${it.text}" }
        writeAtomically(messagesFile, text.toByteArray(Charsets.UTF_8))
    }

    private fun loadState(): Properties = Properties().apply {
        if (stateFile.exists()) stateFile.inputStream().use { load(it) }
    }

    private fun updateState(change: (Properties) -> Unit) {
        val state = loadState()
        change(state)
        dir.mkdirs()
        val tmp = File(dir, "$STATE_FILE.tmp")
        tmp.outputStream().use { state.store(it, null) }
        if (!tmp.renameTo(stateFile)) {
            stateFile.outputStream().use { state.store(it, null) }
            tmp.delete()
        }
    }

    private fun writeAtomically(file: File, bytes: ByteArray) {
        dir.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            file.writeBytes(bytes)
            tmp.delete()
        }
    }
}
