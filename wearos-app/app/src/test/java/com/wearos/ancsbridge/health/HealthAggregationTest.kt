package com.wearos.ancsbridge.health

import org.junit.Assert.*
import org.junit.Test

class HealthAggregationTest {

    private val type = HealthProtocol.TYPE_STEPS
    private val day = 20_000L
    private val dayStart = 1_728_000_000L

    private fun snap(dayKey: Long, end: Long, total: Double, start: Long = dayStart + (dayKey - day) * 86_400) =
        DailyTotals.Snapshot(dayKey, start, end, total)

    // --- SleepSessions ---

    @Test
    fun `asleep then awake records a session`() {
        val asleep = SleepSessions.onStateChange(null, isAsleep = true, atEpochSec = 1_000)
        assertEquals(1_000L, asleep.asleepSinceEpochSec)
        assertNull(asleep.session)

        val awake = SleepSessions.onStateChange(asleep.asleepSinceEpochSec, isAsleep = false, atEpochSec = 1_000 + 7 * 3600)
        assertNull(awake.asleepSinceEpochSec)
        assertEquals(HealthRecord(HealthProtocol.TYPE_SLEEP, 1_000, 1_000 + 7 * 3600, 0.0), awake.session)
    }

    @Test
    fun `repeated asleep reports keep the first start`() {
        val update = SleepSessions.onStateChange(1_000, isAsleep = true, atEpochSec = 5_000)
        assertEquals(1_000L, update.asleepSinceEpochSec)
    }

    @Test
    fun `too short or too long sessions are dropped`() {
        assertNull(SleepSessions.onStateChange(1_000, false, 1_000 + 60).session)
        assertNull(SleepSessions.onStateChange(1_000, false, 1_000 + 30 * 3600).session)
        assertNull(SleepSessions.onStateChange(1_000, false, 500).session) // out of order
        assertNull(SleepSessions.onStateChange(null, false, 5_000).session)
    }

    // --- DailyTotals ---

    @Test
    fun `first reading covers the day so far`() {
        val r = DailyTotals.delta(type, null, snap(day, dayStart + 3600, 500.0))
        assertEquals(HealthRecord(type, dayStart, dayStart + 3600, 500.0), r.record)
    }

    @Test
    fun `same day emits the increase since the last reading`() {
        val prev = snap(day, dayStart + 3600, 500.0)
        val r = DailyTotals.delta(type, prev, snap(day, dayStart + 7200, 800.0))
        assertEquals(HealthRecord(type, dayStart + 3600, dayStart + 7200, 300.0), r.record)
        assertEquals(800.0, r.snapshot.total, 0.0)
    }

    @Test
    fun `no increase and stale readings emit nothing`() {
        val prev = snap(day, dayStart + 3600, 500.0)
        assertNull(DailyTotals.delta(type, prev, snap(day, dayStart + 7200, 500.0)).record)
        val stale = DailyTotals.delta(type, prev, snap(day, dayStart + 1800, 400.0))
        assertNull(stale.record)
        assertEquals(prev, stale.snapshot)
    }

    @Test
    fun `counter drop within a day does not double count`() {
        val prev = snap(day, dayStart + 3600, 500.0)
        assertNull(DailyTotals.delta(type, prev, snap(day, dayStart + 7200, 100.0)).record)
    }

    @Test
    fun `reset after midnight starts from the new day`() {
        val prev = snap(day, dayStart + 86_000, 9_000.0)
        val cur = snap(day + 1, dayStart + 86_400 + 1800, 200.0)
        val r = DailyTotals.delta(type, prev, cur)
        assertEquals(HealthRecord(type, dayStart + 86_400, dayStart + 86_400 + 1800, 200.0), r.record)
    }

    @Test
    fun `counter not yet reset just after midnight keeps counting yesterday`() {
        val prev = snap(day, dayStart + 86_000, 9_000.0)
        val cur = snap(day + 1, dayStart + 86_400 + 120, 9_050.0)
        val r = DailyTotals.delta(type, prev, cur)
        assertEquals(HealthRecord(type, dayStart + 86_000, dayStart + 86_400 + 120, 50.0), r.record)
    }

    @Test
    fun `long gap across days assumes the counter reset`() {
        val prev = snap(day, dayStart + 80_000, 3_000.0)
        val cur = snap(day + 1, dayStart + 86_400 + 72_000, 8_000.0)
        val r = DailyTotals.delta(type, prev, cur)
        assertEquals(HealthRecord(type, dayStart + 86_400, dayStart + 86_400 + 72_000, 8_000.0), r.record)
    }
}
