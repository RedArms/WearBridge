package com.wearos.ancsbridge.health

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The internet path of the phone relay: health batches go from the relay phone
 * to the iPhone through a mailbox (an ntfy topic), end-to-end encrypted with a
 * key only the two of them have. Mirrored in ios-app (RelayMailbox.swift),
 * with a shared golden test vector.
 *
 * Message text = base64url(nonce:12 | AES-256-GCM(plaintext) | tag:16), AAD = [AAD].
 * Plaintext    = kind:u8 | body
 *   kind 1 (batch): seq:u32 | HealthProtocol batch
 *   kind 2 (ack):   count:u16 | seq:u32 × count     (iPhone → relay, on "<topic>-ack")
 * All integers little-endian.
 */
object MailboxCodec {

    const val KIND_BATCH = 1
    const val KIND_ACK = 2
    const val KEY_SIZE = 32
    const val NONCE_SIZE = 12
    private const val TAG_BITS = 128
    val AAD = "wearbridge.relay.v1".toByteArray(Charsets.US_ASCII)

    /** ntfy's message limit; longer bodies would become attachments. */
    const val MAX_MESSAGE_LENGTH = 4096

    /**
     * Records per batch message so the message stays under [MAX_MESSAGE_LENGTH]:
     * base64 of (12 + 16 + 1 + 4 + 2 + 17n) bytes ≤ 4096.
     */
    const val MAX_RECORDS_PER_MESSAGE = 170

    const val LINK_SCHEME = "wearbridge"
    const val LINK_HOST = "relay"
    const val ACK_TOPIC_SUFFIX = "-ack"
    const val DEFAULT_SERVER = "https://ntfy.sh"

    sealed class Message {
        data class Batch(val seq: Long, val records: List<HealthRecord>) : Message()
        data class Ack(val seqs: List<Long>) : Message()
    }

    data class Pairing(val server: String, val topic: String, val key: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Pairing && server == other.server && topic == other.topic && key.contentEquals(other.key)

        override fun hashCode(): Int = (server.hashCode() * 31 + topic.hashCode()) * 31 + key.contentHashCode()
    }

    private val base64 = Base64.getUrlEncoder().withoutPadding()
    private val base64Decoder = Base64.getUrlDecoder()

    // --- Messages ---

    fun sealBatch(key: ByteArray, seq: Long, records: List<HealthRecord>, nonce: ByteArray = randomNonce()): String {
        require(records.size in 1..MAX_RECORDS_PER_MESSAGE) { "1..$MAX_RECORDS_PER_MESSAGE records per message" }
        require(seq in 0..0xFFFF_FFFFL) { "seq out of range" }
        val batch = HealthProtocol.encodeBatch(records)
        val plain = ByteBuffer.allocate(1 + 4 + batch.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(KIND_BATCH.toByte()).putInt(seq.toInt()).put(batch).array()
        return seal(key, plain, nonce)
    }

    fun sealAck(key: ByteArray, seqs: List<Long>, nonce: ByteArray = randomNonce()): String {
        require(seqs.size in 1..0xFFFF) { "1..65535 seqs per ack" }
        val plain = ByteBuffer.allocate(1 + 2 + 4 * seqs.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(KIND_ACK.toByte()).putShort(seqs.size.toShort())
        seqs.forEach { plain.putInt(it.toInt()) }
        return seal(key, plain.array(), nonce)
    }

    /** Decrypts and parses a message, or null if it isn't ours (wrong key, tampered, malformed). */
    fun open(key: ByteArray, text: String): Message? {
        val plain = unseal(key, text) ?: return null
        if (plain.isEmpty()) return null
        val buffer = ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN)
        return when (buffer.get().toInt() and 0xFF) {
            KIND_BATCH -> {
                if (buffer.remaining() < 4) return null
                val seq = buffer.int.toLong() and 0xFFFF_FFFFL
                val batch = ByteArray(buffer.remaining()).also { buffer.get(it) }
                HealthProtocol.decodeBatch(batch)?.let { Message.Batch(seq, it) }
            }
            KIND_ACK -> {
                if (buffer.remaining() < 2) return null
                val count = buffer.short.toInt() and 0xFFFF
                if (buffer.remaining() != count * 4) return null
                Message.Ack(List(count) { buffer.int.toLong() and 0xFFFF_FFFFL })
            }
            else -> null
        }
    }

    private fun seal(key: ByteArray, plain: ByteArray, nonce: ByteArray): String {
        require(key.size == KEY_SIZE) { "AES-256 key expected" }
        require(nonce.size == NONCE_SIZE) { "12-byte nonce expected" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(AAD)
        return base64.encodeToString(nonce + cipher.doFinal(plain))
    }

    private fun unseal(key: ByteArray, text: String): ByteArray? {
        if (key.size != KEY_SIZE) return null
        val sealed = try {
            base64Decoder.decode(text.trim())
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (sealed.size < NONCE_SIZE + TAG_BITS / 8) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_SIZE))
            cipher.updateAAD(AAD)
            cipher.doFinal(sealed, NONCE_SIZE, sealed.size - NONCE_SIZE)
        } catch (e: AEADBadTagException) {
            null
        }
    }

    private fun randomNonce(): ByteArray = ByteArray(NONCE_SIZE).also { SecureRandom().nextBytes(it) }

    // --- Pairing ---

    /** A fresh pairing: random topic and key. */
    fun newPairing(server: String = DEFAULT_SERVER, random: SecureRandom = SecureRandom()): Pairing {
        val topicBytes = ByteArray(18).also { random.nextBytes(it) }
        val key = ByteArray(KEY_SIZE).also { random.nextBytes(it) }
        // ntfy topics: [-_A-Za-z0-9]{1,64}; base64url fits, 24 chars ≈ 144 random bits.
        return Pairing(server.trimEnd('/'), "wb-" + base64.encodeToString(topicBytes), key)
    }

    /** wearbridge://relay?s=<server>&t=<topic>&k=<key>, scanned or opened on the iPhone. */
    fun pairingLink(pairing: Pairing): String =
        "$LINK_SCHEME://$LINK_HOST?s=${URLEncoder.encode(pairing.server, "UTF-8")}" +
            "&t=${pairing.topic}&k=${base64.encodeToString(pairing.key)}"

    fun parsePairingLink(link: String): Pairing? {
        val prefix = "$LINK_SCHEME://$LINK_HOST?"
        if (!link.startsWith(prefix)) return null
        val params = link.removePrefix(prefix).split('&').mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) null else part.substring(0, eq) to URLDecoder.decode(part.substring(eq + 1), "UTF-8")
        }.toMap()
        val server = params["s"]?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return null
        val topic = params["t"]?.takeIf { it.matches(Regex("[-_A-Za-z0-9]{1,60}")) } ?: return null
        val key = try {
            base64Decoder.decode(params["k"] ?: return null)
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (key.size != KEY_SIZE) return null
        return Pairing(server.trimEnd('/'), topic, key)
    }
}
