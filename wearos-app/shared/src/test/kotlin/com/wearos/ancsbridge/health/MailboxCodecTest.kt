package com.wearos.ancsbridge.health

import org.junit.Assert.*
import org.junit.Test

class MailboxCodecTest {

    private val key = ByteArray(32) { it.toByte() }
    private val nonce = ByteArray(12) { it.toByte() }
    private val heartRate = HealthRecord(HealthProtocol.TYPE_HEART_RATE, 1_760_000_000, 1_760_000_000, 72.5)
    private val steps = HealthRecord(HealthProtocol.TYPE_STEPS, 1_760_000_000, 1_760_000_060, 123.0)

    /** Computed independently (Python cryptography, AES-GCM); also checked by the iOS tests. */
    private val goldenBatch =
        "AAECAwQFBgcICQoLRgXWG8XkwBqNOXDjsZGfBYPWhzTwWw08OmedYnVVeFVpEK78r8HSxjROJLxyLPNBnLlpiyWljhVc"
    private val goldenAck = "DA0ODxAREhMUFRYXmvxp3392_Fs3Kki6E7A7A6xfCGEZjAhnT7LU"

    @Test
    fun `sealBatch matches the golden vector`() {
        assertEquals(goldenBatch, MailboxCodec.sealBatch(key, 7, listOf(heartRate, steps), nonce))
    }

    @Test
    fun `open reads the golden vectors`() {
        assertEquals(MailboxCodec.Message.Batch(7, listOf(heartRate, steps)), MailboxCodec.open(key, goldenBatch))
        assertEquals(MailboxCodec.Message.Ack(listOf(7L, 8L)), MailboxCodec.open(key, goldenAck))
        assertEquals(goldenAck, MailboxCodec.sealAck(key, listOf(7, 8), ByteArray(12) { (it + 12).toByte() }))
    }

    @Test
    fun `wrong key, tampering and garbage are rejected`() {
        val otherKey = ByteArray(32) { 7 }
        assertNull(MailboxCodec.open(otherKey, goldenBatch))
        val tampered = goldenBatch.substring(0, 30) + (if (goldenBatch[30] == 'A') 'B' else 'A') + goldenBatch.substring(31)
        assertNull(MailboxCodec.open(key, tampered))
        assertNull(MailboxCodec.open(key, "not base64 !!"))
        assertNull(MailboxCodec.open(key, ""))
    }

    @Test
    fun `a full message stays under the ntfy limit`() {
        val records = List(MailboxCodec.MAX_RECORDS_PER_MESSAGE) { steps.copy(startEpochSec = steps.startEpochSec + it, endEpochSec = steps.endEpochSec + it) }
        val text = MailboxCodec.sealBatch(key, 0xFFFF_FFFFL, records)
        assertTrue(text.length <= MailboxCodec.MAX_MESSAGE_LENGTH)
        assertEquals(MailboxCodec.Message.Batch(0xFFFF_FFFFL, records), MailboxCodec.open(key, text))
    }

    @Test
    fun `pairing link round trip`() {
        val pairing = MailboxCodec.newPairing("https://ntfy.example.org/")
        assertEquals("https://ntfy.example.org", pairing.server)
        assertTrue(pairing.topic.matches(Regex("wb-[-_A-Za-z0-9]{24}")))
        val link = MailboxCodec.pairingLink(pairing)
        assertTrue(link.startsWith("wearbridge://relay?s=https%3A%2F%2Fntfy.example.org&t=wb-"))
        assertEquals(pairing, MailboxCodec.parsePairingLink(link))
    }

    @Test
    fun `bad pairing links are rejected`() {
        val good = MailboxCodec.pairingLink(MailboxCodec.newPairing())
        assertNull(MailboxCodec.parsePairingLink(good.replace("wearbridge://", "https://")))
        assertNull(MailboxCodec.parsePairingLink(good.substringBefore("&k=")))
        assertNull(MailboxCodec.parsePairingLink(good.replace("s=https", "s=ftp")))
        assertNull(MailboxCodec.parsePairingLink(good.substringBefore("&k=") + "&k=AAAA"))
    }
}
