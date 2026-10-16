package com.wearos.ancsbridge.health

import org.junit.Assert.*
import org.junit.Test

class HealthProtocolTest {

    private val heartRate = HealthRecord(HealthProtocol.TYPE_HEART_RATE, 1_760_000_000, 1_760_000_000, 72.5)
    private val steps = HealthRecord(HealthProtocol.TYPE_STEPS, 1_760_000_000, 1_760_000_060, 123.0)

    // Same vector as testHealthBatchGoldenVector in the iOS tests.
    private val golden = hex(
        "01 02 01 00 78 E7 68 00 78 E7 68 00 00 00 00 00 20 52 40 " +
            "02 00 78 E7 68 3C 78 E7 68 00 00 00 00 00 C0 5E 40"
    )

    @Test
    fun `encodeBatch matches the golden vector`() {
        assertArrayEquals(golden, HealthProtocol.encodeBatch(listOf(heartRate, steps)))
    }

    @Test
    fun `decodeBatch reads the golden vector`() {
        assertEquals(listOf(heartRate, steps), HealthProtocol.decodeBatch(golden))
    }

    @Test
    fun `timestamps above 2^31 survive the unsigned round trip`() {
        val late = HealthRecord(HealthProtocol.TYPE_SLEEP, 3_000_000_000, 3_000_028_800, 0.0)
        assertEquals(listOf(late), HealthProtocol.decodeBatch(HealthProtocol.encodeBatch(listOf(late))))
    }

    @Test
    fun `decodeBatch rejects malformed payloads`() {
        assertNull(HealthProtocol.decodeBatch(ByteArray(0)))
        assertNull(HealthProtocol.decodeBatch(byteArrayOf(0x01)))
        assertNull(HealthProtocol.decodeBatch(byteArrayOf(0x02, 0x01) + ByteArray(17))) // unknown version
        assertNull(HealthProtocol.decodeBatch(byteArrayOf(0x01, 0x00))) // empty batch
        assertNull(HealthProtocol.decodeBatch(golden.copyOf(golden.size - 1))) // truncated
        assertNull(HealthProtocol.decodeBatch(golden + 0x00)) // trailing byte
    }

    @Test
    fun `encodeBatch refuses empty or oversized batches`() {
        assertThrows(IllegalArgumentException::class.java) { HealthProtocol.encodeBatch(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { HealthProtocol.encodeBatch(List(256) { heartRate }) }
    }

    @Test
    fun `isValid rejects bad records`() {
        assertTrue(HealthProtocol.isValid(heartRate))
        assertFalse(HealthProtocol.isValid(heartRate.copy(type = 99)))
        assertFalse(HealthProtocol.isValid(steps.copy(endEpochSec = steps.startEpochSec - 1)))
        assertFalse(HealthProtocol.isValid(steps.copy(startEpochSec = -1)))
        assertFalse(HealthProtocol.isValid(steps.copy(endEpochSec = 0x1_0000_0000L)))
        assertFalse(HealthProtocol.isValid(steps.copy(value = -1.0)))
        assertFalse(HealthProtocol.isValid(steps.copy(value = Double.NaN)))
    }

    @Test
    fun `type specific values are checked`() {
        fun r(type: Int, value: Double) = HealthRecord(type, 1_000, 1_060, value)
        assertTrue(HealthProtocol.isValid(r(HealthProtocol.TYPE_SLEEP, HealthProtocol.SLEEP_STAGE_REM.toDouble())))
        assertTrue(HealthProtocol.isValid(r(HealthProtocol.TYPE_SLEEP, HealthProtocol.SLEEP_STAGE_WATCH.toDouble())))
        assertFalse(HealthProtocol.isValid(r(HealthProtocol.TYPE_SLEEP, 6.0)))
        assertFalse(HealthProtocol.isValid(r(HealthProtocol.TYPE_SLEEP, 1.5)))
        assertTrue(HealthProtocol.isValid(r(HealthProtocol.TYPE_IN_BED, 82.0)))
        assertFalse(HealthProtocol.isValid(r(HealthProtocol.TYPE_IN_BED, 101.0)))
        assertTrue(HealthProtocol.isValid(r(HealthProtocol.TYPE_OXYGEN_SATURATION, 96.5)))
        assertFalse(HealthProtocol.isValid(r(HealthProtocol.TYPE_OXYGEN_SATURATION, 0.0)))
        assertFalse(HealthProtocol.isValid(r(HealthProtocol.TYPE_OXYGEN_SATURATION, 120.0)))
        assertTrue(HealthProtocol.isValid(r(HealthProtocol.TYPE_BLOOD_PRESSURE, HealthProtocol.bloodPressureValue(121.4, 79.6))))
        assertFalse(HealthProtocol.isValid(r(HealthProtocol.TYPE_BLOOD_PRESSURE, 120.0))) // diastolic only
    }

    @Test
    fun `blood pressure packs into one value`() {
        val value = HealthProtocol.bloodPressureValue(121.4, 79.6)
        assertEquals(121_080.0, value, 0.0)
        assertEquals(121 to 80, HealthProtocol.splitBloodPressure(value))
    }

    @Test
    fun `maxRecordsPerWrite fits the MTU`() {
        assertEquals(1, HealthProtocol.maxRecordsPerWrite(23))   // BLE default MTU
        assertEquals(10, HealthProtocol.maxRecordsPerWrite(185)) // common iPhone MTU
        assertEquals(30, HealthProtocol.maxRecordsPerWrite(517)) // capped by the 512-byte attribute limit
        for (mtu in 23..517) {
            val n = HealthProtocol.maxRecordsPerWrite(mtu)
            val size = HealthProtocol.HEADER_SIZE + n * HealthProtocol.RECORD_SIZE
            assertTrue("mtu=$mtu", size <= minOf(mtu - 3, HealthProtocol.MAX_ATTRIBUTE_SIZE))
        }
    }

    private fun hex(s: String): ByteArray =
        s.split(" ").filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()
}
