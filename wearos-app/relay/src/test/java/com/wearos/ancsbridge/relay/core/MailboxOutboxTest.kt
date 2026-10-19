package com.wearos.ancsbridge.relay.core

import com.wearos.ancsbridge.health.HealthProtocol
import com.wearos.ancsbridge.health.HealthRecord
import com.wearos.ancsbridge.health.MailboxCodec
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MailboxOutboxTest {

    private lateinit var dir: File
    private val key = ByteArray(32) { 3 }
    private val t0 = 1_760_000_000L

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("outbox").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun hr(i: Int) = HealthRecord(HealthProtocol.TYPE_HEART_RATE, t0 + i, t0 + i, 60.0 + i % 30)
    private val sleep = HealthRecord(HealthProtocol.TYPE_SLEEP, t0, t0 + 3600, HealthProtocol.SLEEP_STAGE_DEEP.toDouble())

    private fun decode(entry: MailboxOutbox.Entry) = MailboxCodec.open(key, entry.text) as MailboxCodec.Message.Batch

    @Test
    fun `heart rate waits to be grouped, other types go out at once`() {
        val outbox = MailboxOutbox(dir)
        outbox.add(listOf(hr(0), hr(1)), t0)
        assertEquals(0, outbox.pack(key, t0 + 600))
        assertEquals(1, outbox.pack(key, t0 + MailboxOutbox.HEART_RATE_DELAY_SEC))
        assertEquals(listOf(hr(0), hr(1)), decode(outbox.due(t0).single()).records)

        outbox.add(listOf(sleep), t0 + 4000)
        assertEquals(1, outbox.pack(key, t0 + 4000))
        assertEquals(listOf(0L, 1L), outbox.due(t0 + 4000).map { it.seq })
    }

    @Test
    fun `large batches split into messages that fit the mailbox`() {
        val outbox = MailboxOutbox(dir)
        outbox.add((0 until 400).map(::hr), t0)
        assertEquals(3, outbox.pack(key, t0))
        val messages = outbox.due(t0)
        assertEquals(400, messages.sumOf { decode(it).records.size })
        assertTrue(messages.all { it.text.length <= MailboxCodec.MAX_MESSAGE_LENGTH })
        assertEquals(0, outbox.pendingCount())
    }

    @Test
    fun `posted messages are reposted until acknowledged`() {
        val outbox = MailboxOutbox(dir)
        outbox.add(listOf(sleep), t0)
        outbox.pack(key, t0)
        outbox.markPosted(0, t0)
        assertTrue(outbox.due(t0 + 60).isEmpty())
        assertEquals(1, outbox.due(t0 + MailboxOutbox.REPOST_AFTER_SEC).size)
        outbox.acknowledge(listOf(0L, 42L), t0 + 100)
        assertTrue(outbox.due(t0 + MailboxOutbox.REPOST_AFTER_SEC).isEmpty())
        assertEquals(0, outbox.waitingForAck())
    }

    @Test
    fun `new messages go out before reposts`() {
        val outbox = MailboxOutbox(dir)
        outbox.add(listOf(sleep), t0)
        outbox.pack(key, t0)
        outbox.markPosted(0, t0)
        outbox.add(listOf(sleep.copy(startEpochSec = t0 + 10)), t0 + 1)
        outbox.pack(key, t0 + 1)
        assertEquals(listOf(1L, 0L), outbox.due(t0 + MailboxOutbox.REPOST_AFTER_SEC).map { it.seq })
    }

    @Test
    fun `posts stay under the daily limit`() {
        val outbox = MailboxOutbox(dir)
        outbox.add((0 until 170 * 200).map(::hr), t0)
        assertEquals(200, outbox.pack(key, t0))
        var now = t0
        var posted = 0
        while (true) {
            val due = outbox.due(now)
            if (due.isEmpty()) break
            assertTrue(due.size <= MailboxOutbox.MAX_POSTS_PER_RUN)
            due.forEach { outbox.markPosted(it.seq, now); posted++ }
            now += 900
        }
        assertEquals(MailboxOutbox.MAX_POSTS_PER_DAY, posted)
        assertEquals(MailboxOutbox.MAX_POSTS_PER_DAY, outbox.postsToday(now))
        assertTrue(outbox.due(t0 + 24 * 3600).isNotEmpty()) // a day later the budget is back
    }

    @Test
    fun `without an iPhone listening, one message per repost interval`() {
        val outbox = MailboxOutbox(dir)
        outbox.add((0 until 500).map(::hr), t0)
        outbox.pack(key, t0)
        val first = outbox.due(t0, listening = false)
        assertEquals(listOf(0L), first.map { it.seq })
        outbox.markPosted(0, t0)
        assertTrue(outbox.due(t0 + 3600, listening = false).isEmpty())
        assertEquals(listOf(1L), outbox.due(t0 + MailboxOutbox.REPOST_AFTER_SEC, listening = false).map { it.seq })
        // The iPhone answers: everything queued follows.
        assertNull(outbox.lastAckSec)
        outbox.acknowledge(listOf(0L), t0 + 3600)
        assertEquals(t0 + 3600, outbox.lastAckSec)
        assertEquals(listOf(1L, 2L), outbox.due(t0 + 3600, listening = true).map { it.seq })
    }

    @Test
    fun `clearing keeps sequence numbers and the daily post count`() {
        val outbox = MailboxOutbox(dir)
        outbox.add(listOf(sleep), t0)
        outbox.pack(key, t0)
        outbox.markPosted(0, t0)
        outbox.acknowledge(emptyList(), t0)
        outbox.clear()
        assertEquals(1, outbox.postsToday(t0 + 60))
        assertNull(outbox.lastAckSec)
        outbox.add(listOf(sleep), t0)
        outbox.pack(key, t0)
        assertEquals(listOf(1L), outbox.due(t0).map { it.seq })
    }

    @Test
    fun `unacknowledged messages are given up after a week`() {
        val outbox = MailboxOutbox(dir)
        outbox.add(listOf(sleep), t0)
        outbox.pack(key, t0)
        assertEquals(0, outbox.dropExpired(t0 + 3600))
        assertEquals(1, outbox.dropExpired(t0 + MailboxOutbox.GIVE_UP_AFTER_SEC))
        assertEquals(0, outbox.waitingForAck())
    }

    @Test
    fun `state survives a new instance and sequence numbers keep going`() {
        MailboxOutbox(dir).apply {
            add(listOf(sleep), t0)
            pack(key, t0)
            lastAckId = "abc123"
        }
        val reopened = MailboxOutbox(dir)
        assertEquals("abc123", reopened.lastAckId)
        assertEquals(1, reopened.waitingForAck())
        reopened.add(listOf(sleep.copy(startEpochSec = t0 + 10)), t0)
        reopened.pack(key, t0)
        assertEquals(listOf(0L, 1L), reopened.due(t0).map { it.seq })
        reopened.clear()
        reopened.add(listOf(sleep), t0)
        reopened.pack(key, t0)
        assertEquals(listOf(2L), reopened.due(t0).map { it.seq }) // seqs never reused
    }
}
