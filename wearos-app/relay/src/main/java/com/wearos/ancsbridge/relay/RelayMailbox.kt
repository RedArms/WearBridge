package com.wearos.ancsbridge.relay

import android.content.Context
import android.util.Base64
import android.util.Log
import com.wearos.ancsbridge.health.HealthRecord
import com.wearos.ancsbridge.health.MailboxCodec
import com.wearos.ancsbridge.relay.core.MailboxOutbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The internet path to the iPhone: records go into a [MailboxOutbox], sealed
 * with the pairing key, and are posted to an ntfy topic the iPhone app polls.
 * Works wherever the iPhone is; the watch doesn't need internet.
 */
class RelayMailbox(context: Context) {

    companion object {
        private const val TAG = "RelayMailbox"
        private const val PREFS = "relay_mailbox"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_SERVER = "server"
        private const val KEY_TOPIC = "topic"
        private const val KEY_KEY = "key"
        private const val KEY_LAST_POST = "last_post"
        private const val TIMEOUT_MS = 15_000
    }

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val outbox = MailboxOutbox(File(app.filesDir, "mailbox"))

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** The pairing, created on first use. Its link goes to the iPhone (QR code). */
    val pairing: MailboxCodec.Pairing
        @Synchronized get() {
            val server = prefs.getString(KEY_SERVER, null)
            val topic = prefs.getString(KEY_TOPIC, null)
            val key = prefs.getString(KEY_KEY, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
            if (server != null && topic != null && key != null && key.size == MailboxCodec.KEY_SIZE) {
                return MailboxCodec.Pairing(server, topic, key)
            }
            return MailboxCodec.newPairing(server ?: MailboxCodec.DEFAULT_SERVER).also { save(it) }
        }

    val link: String get() = MailboxCodec.pairingLink(pairing)

    val lastPostEpochSec: Long? get() = prefs.getLong(KEY_LAST_POST, -1L).takeIf { it >= 0 }

    /** A new topic and key: the iPhone has to scan the new QR code. */
    @Synchronized
    fun reset(server: String = pairing.server) {
        save(MailboxCodec.newPairing(server))
        outbox.clear()
    }

    private fun save(pairing: MailboxCodec.Pairing) {
        prefs.edit()
            .putString(KEY_SERVER, pairing.server)
            .putString(KEY_TOPIC, pairing.topic)
            .putString(KEY_KEY, Base64.encodeToString(pairing.key, Base64.NO_WRAP))
            .apply()
    }

    /** Queues records for the iPhone (durably; sending happens in [sync]). */
    fun enqueue(records: List<HealthRecord>, nowSec: Long) {
        if (enabled) outbox.add(records, nowSec)
    }

    /**
     * Reads the iPhone's acks, packs and posts what's due, drops what's too old.
     * Network errors leave everything queued for the next run.
     */
    suspend fun sync(nowSec: Long): String = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext "Internet relay off"
        val pairing = pairing
        try {
            readAcks(pairing)
            outbox.pack(pairing.key, nowSec)
            var posted = 0
            for (entry in outbox.due(nowSec)) {
                if (!post(pairing.server, pairing.topic, entry.text)) break
                outbox.markPosted(entry.seq, nowSec)
                posted++
            }
            if (posted > 0) prefs.edit().putLong(KEY_LAST_POST, nowSec).apply()
            val dropped = outbox.dropExpired(nowSec)
            if (dropped > 0) Log.w(TAG, "Gave up on $dropped messages the iPhone never confirmed")
            "Internet relay: $posted posted, ${outbox.waitingForAck()} waiting for the iPhone, " +
                "${outbox.pendingCount()} records to group"
        } catch (e: Exception) {
            Log.w(TAG, "Mailbox sync failed", e)
            "Internet relay: no connection (${e.message ?: e.javaClass.simpleName}); will retry"
        }
    }

    private fun readAcks(pairing: MailboxCodec.Pairing) {
        val ackTopic = pairing.topic + MailboxCodec.ACK_TOPIC_SUFFIX
        val since = URLEncoder.encode(outbox.lastAckId ?: "all", "UTF-8")
        val connection = URL("${pairing.server}/$ackTopic/json?poll=1&since=$since").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            if (connection.responseCode != 200) throw IllegalStateException("ack poll: HTTP ${connection.responseCode}")
            var lastId: String? = null
            val acked = mutableListOf<Long>()
            connection.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    val json = JSONObject(line)
                    if (json.optString("event") != "message") continue
                    json.optString("id").takeIf { it.isNotEmpty() }?.let { lastId = it }
                    val ack = MailboxCodec.open(pairing.key, json.optString("message")) as? MailboxCodec.Message.Ack
                    ack?.let { acked += it.seqs }
                }
            }
            outbox.acknowledge(acked)
            lastId?.let { outbox.lastAckId = it }
        } finally {
            connection.disconnect()
        }
    }

    private fun post(server: String, topic: String, text: String): Boolean {
        val connection = URL("$server/$topic").openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            connection.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) Log.w(TAG, "Post refused: HTTP $code")
            code in 200..299
        } finally {
            connection.disconnect()
        }
    }
}
