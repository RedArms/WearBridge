package com.wearos.ancsbridge.alarm

/**
 * The vibration pattern of the wake-up alarm: barely-there taps that grow, over
 * [RAMP_MS], into long strong buzzes, which then repeat until the alarm stops.
 * Pure Kotlin so the shape is unit tested; [WakeAlarm] plays it.
 *
 * As Android waveform arrays: [timings] alternates off/on durations (starting
 * with off), [amplitudes] gives 0 for off and 1..255 for on, and [repeatIndex]
 * is where the steady final pattern starts repeating.
 */
class WakeRamp private constructor(
    val timings: LongArray,
    val amplitudes: IntArray,
    val repeatIndex: Int
) {

    companion object {
        const val RAMP_MS = 60_000L

        private const val STEPS = 12
        private const val FIRST_PULSE_MS = 60L
        private const val LAST_PULSE_MS = 650L
        private const val FIRST_PERIOD_MS = 2_400L
        private const val LAST_PERIOD_MS = 1_100L
        private const val FIRST_AMPLITUDE = 30
        private const val LAST_AMPLITUDE = 255

        /**
         * The ramp. Without amplitude control (some watches), only pulse length
         * and spacing grow, which still reads as getting stronger.
         */
        fun build(): WakeRamp {
            val timings = mutableListOf<Long>()
            val amplitudes = mutableListOf<Int>()
            val stepMs = RAMP_MS / STEPS
            for (step in 0 until STEPS) {
                val t = step.toDouble() / (STEPS - 1)
                // Ease in: stay gentle for a while, then grow faster.
                val eased = t * t
                val pulse = lerp(FIRST_PULSE_MS, LAST_PULSE_MS, eased)
                val period = lerp(FIRST_PERIOD_MS, LAST_PERIOD_MS, eased)
                val amplitude = lerp(FIRST_AMPLITUDE.toLong(), LAST_AMPLITUDE.toLong(), eased).toInt()
                var elapsed = 0L
                while (elapsed + period <= stepMs) {
                    timings += period - pulse
                    amplitudes += 0
                    timings += pulse
                    amplitudes += amplitude
                    elapsed += period
                }
            }
            // Steady final pattern: two strong buzzes, then a breath.
            val repeatIndex = timings.size
            timings += listOf(400L, LAST_PULSE_MS, 250L, LAST_PULSE_MS)
            amplitudes += listOf(0, LAST_AMPLITUDE, 0, LAST_AMPLITUDE)
            return WakeRamp(timings.toLongArray(), amplitudes.toIntArray(), repeatIndex)
        }

        private fun lerp(from: Long, to: Long, t: Double): Long = (from + (to - from) * t).toLong()
    }

    /** Total length of the ramp before the steady pattern repeats. */
    val rampDurationMs: Long get() = timings.take(repeatIndex).sum()
}
