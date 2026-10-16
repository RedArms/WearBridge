package com.wearos.ancsbridge.health

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class HealthRecordStoreTest {

    private lateinit var dir: File
    private lateinit var file: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("health-store").toFile()
        file = File(dir, "queue.bin")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun record(i: Int) = HealthRecord(HealthProtocol.TYPE_STEPS, 1_000L + i, 1_060L + i, i.toDouble())

    @Test
    fun `peek and ack in FIFO order`() {
        val store = HealthRecordStore(file)
        store.append((0 until 5).map(::record))

        val first = store.peek(2)
        assertEquals(listOf(record(0), record(1)), first.records)
        store.ack(first)

        assertEquals(3, store.size())
        assertEquals(listOf(record(2), record(3), record(4)), store.peek(10).records)
    }

    @Test
    fun `records appended while a batch is in flight are kept`() {
        val store = HealthRecordStore(file)
        store.append(listOf(record(0), record(1)))
        val batch = store.peek(10)
        store.append(listOf(record(2)))
        store.ack(batch)
        assertEquals(listOf(record(2)), store.peek(10).records)
    }

    @Test
    fun `partial ack keeps the unconfirmed tail of a batch`() {
        val store = HealthRecordStore(file)
        store.append((0 until 5).map(::record))
        val batch = store.peek(4)
        store.ack(batch, 3)
        assertEquals(listOf(record(3), record(4)), store.peek(10).records)
        store.ack(store.peek(10), 0)
        assertEquals(2, store.size())
    }

    @Test
    fun `partial ack after the cap trimmed the head removes only what is left`() {
        val store = HealthRecordStore(file, maxRecords = 4)
        store.append((0 until 4).map(::record))
        val batch = store.peek(3) // records 0, 1, 2
        store.append(listOf(record(4), record(5))) // cap trims records 0 and 1
        store.ack(batch, 2) // records 0 and 1 confirmed, already gone
        assertEquals(listOf(record(2), record(3), record(4), record(5)), store.peek(10).records)
    }

    @Test
    fun `queue survives a new instance`() {
        HealthRecordStore(file).append(listOf(record(0), record(1)))
        assertEquals(listOf(record(0), record(1)), HealthRecordStore(file).peek(10).records)
    }

    @Test
    fun `invalid records are not stored`() {
        val store = HealthRecordStore(file)
        store.append(listOf(record(0).copy(type = 42), record(1)))
        assertEquals(listOf(record(1)), store.peek(10).records)
    }

    @Test
    fun `cap drops the oldest records`() {
        val store = HealthRecordStore(file, maxRecords = 3)
        store.append((0 until 5).map(::record))
        assertEquals(listOf(record(2), record(3), record(4)), store.peek(10).records)
    }

    @Test
    fun `ack after the cap trimmed the head only removes the rest of the batch`() {
        val store = HealthRecordStore(file, maxRecords = 3)
        store.append((0 until 3).map(::record))
        val batch = store.peek(2) // records 0 and 1
        store.append(listOf(record(3), record(4))) // trims 0 and 1
        store.ack(batch)
        assertEquals(listOf(record(2), record(3), record(4)), store.peek(10).records)
    }

    @Test
    fun `partial record left by a crash is dropped`() {
        val store = HealthRecordStore(file)
        store.append(listOf(record(0)))
        file.appendBytes(byteArrayOf(1, 2, 3)) // torn write
        assertEquals(1, store.size())
        store.append(listOf(record(1)))
        assertEquals(listOf(record(0), record(1)), store.peek(10).records)
    }

    @Test
    fun `empty store peeks nothing`() {
        val store = HealthRecordStore(file)
        val batch = store.peek(10)
        assertTrue(batch.records.isEmpty())
        store.ack(batch)
        assertEquals(0, store.size())
    }
}
