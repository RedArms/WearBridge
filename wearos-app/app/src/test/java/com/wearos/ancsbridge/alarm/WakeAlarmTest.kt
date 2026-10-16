package com.wearos.ancsbridge.alarm

import com.wearos.ancsbridge.ble.AncsConstants
import org.junit.Assert.*
import org.junit.Test

class WakeAlarmTest {

    @Test
    fun `ramp is a valid Android waveform`() {
        val ramp = WakeRamp.build()
        assertEquals(ramp.timings.size, ramp.amplitudes.size)
        assertTrue(ramp.repeatIndex in 1 until ramp.timings.size)
        assertTrue(ramp.timings.all { it >= 0 })
        assertTrue(ramp.amplitudes.all { it in 0..255 })
        // Off/on alternation, starting with off.
        ramp.amplitudes.forEachIndexed { i, a -> if (i % 2 == 0) assertEquals(0, a) else assertTrue(a > 0) }
    }

    @Test
    fun `ramp grows from gentle to strong over about a minute`() {
        val ramp = WakeRamp.build()
        val pulses = ramp.amplitudes.indices.filter { it % 2 == 1 && it < ramp.repeatIndex }
        val first = pulses.first()
        val last = pulses.last()
        assertTrue(ramp.amplitudes[first] < 60)
        assertEquals(255, ramp.amplitudes[last])
        assertTrue(ramp.timings[first] < ramp.timings[last])
        // Never gets weaker along the way.
        pulses.zipWithNext().forEach { (a, b) -> assertTrue(ramp.amplitudes[a] <= ramp.amplitudes[b]) }
        assertTrue(ramp.rampDurationMs in 50_000L..WakeRamp.RAMP_MS)
    }

    @Test
    fun `clock alarm actions are found by label`() {
        val english = ClockAlarm(1, positiveActionLabel = "Snooze", negativeActionLabel = "Stop")
        assertEquals(AncsConstants.ACTION_NEGATIVE, english.stopActionId)
        assertEquals(AncsConstants.ACTION_POSITIVE, english.snoozeActionId)

        val french = ClockAlarm(2, positiveActionLabel = "Arrêter", negativeActionLabel = "Répéter")
        assertEquals(AncsConstants.ACTION_POSITIVE, french.stopActionId)
        assertEquals(AncsConstants.ACTION_NEGATIVE, french.snoozeActionId)

        // Snooze next to an unknown label: the other one stops.
        val unknown = ClockAlarm(3, positiveActionLabel = "Snooze", negativeActionLabel = "Ok")
        assertEquals(AncsConstants.ACTION_NEGATIVE, unknown.stopActionId)

        val timer = ClockAlarm(4, positiveActionLabel = null, negativeActionLabel = "Stop")
        assertEquals(AncsConstants.ACTION_NEGATIVE, timer.stopActionId)
        assertNull(timer.snoozeActionId)
    }

    @Test
    fun `only Clock notifications with actions count as alarms`() {
        assertNotNull(ClockAlarm.from(1, ClockAlarm.CLOCK_APP, "Snooze", "Stop"))
        assertNull(ClockAlarm.from(1, ClockAlarm.CLOCK_APP, null, null))
        assertNull(ClockAlarm.from(1, "com.apple.MobileSMS", "Reply", "Clear"))
    }
}
