package com.wearos.ancsbridge.health

/**
 * Turns Health Services "user activity state" changes into asleep intervals.
 *
 * Health Services only reports state transitions (asleep, passive, exercise…),
 * so the caller persists the start of the current asleep period and feeds every
 * transition through [onStateChange]. A session is emitted when the user stops
 * being asleep. These are plain asleep periods, not sleep stages.
 */
object SleepSessions {

    /** Shorter "asleep" periods are usually the watch misreading a still wrist. */
    const val MIN_SESSION_SEC = 10 * 60L

    /** Longer ones mean a wake-up transition was missed; don't record garbage. */
    const val MAX_SESSION_SEC = 24 * 3600L

    /**
     * @param asleepSinceEpochSec value to persist; null when the user is not asleep.
     * @param session the asleep interval that just ended, if it is worth recording.
     */
    data class Update(val asleepSinceEpochSec: Long?, val session: HealthRecord?)

    fun onStateChange(asleepSinceEpochSec: Long?, isAsleep: Boolean, atEpochSec: Long): Update {
        if (isAsleep) {
            // Repeated "asleep" reports keep the original start.
            return Update(asleepSinceEpochSec ?: atEpochSec, null)
        }
        val start = asleepSinceEpochSec ?: return Update(null, null)
        val duration = atEpochSec - start
        val session = if (duration in MIN_SESSION_SEC..MAX_SESSION_SEC) {
            HealthRecord(HealthProtocol.TYPE_SLEEP, start, atEpochSec, 0.0)
        } else null
        return Update(null, session)
    }
}

/**
 * Converts cumulative daily totals (STEPS_DAILY and friends) into increments.
 *
 * Only used when the watch does not offer the plain delta data types for passive
 * monitoring. The caller persists one [Snapshot] per data type and passes each
 * new reading through [delta].
 */
object DailyTotals {

    /** A gap this long across midnight means the counter has surely reset. */
    const val RESET_GAP_SEC = 3 * 3600L

    /**
     * @param dayKey local calendar day of [endSec] (e.g. epoch day), used to spot midnight.
     * @param dayStartSec start of that local day, in Unix seconds.
     */
    data class Snapshot(val dayKey: Long, val dayStartSec: Long, val endSec: Long, val total: Double)

    data class Result(val snapshot: Snapshot, val record: HealthRecord?)

    fun delta(type: Int, previous: Snapshot?, current: Snapshot): Result {
        if (previous == null) return Result(current, fromDayStart(type, current))
        if (current.endSec <= previous.endSec) return Result(previous, null) // stale or duplicate

        val increase = current.total - previous.total
        if (current.dayKey == previous.dayKey) {
            // Same day: a drop means the platform reset its counter; skip rather than double count.
            val record = if (increase > 0) HealthRecord(type, previous.endSec, current.endSec, increase) else null
            return Result(current, record)
        }

        // A new local day. The platform counter resets around midnight, but not
        // necessarily before the first reading of the day arrives.
        val counterReset = increase < 0 || current.endSec - previous.endSec >= RESET_GAP_SEC
        val record = when {
            counterReset -> fromDayStart(type, current)
            increase > 0 -> HealthRecord(type, previous.endSec, current.endSec, increase)
            else -> null
        }
        return Result(current, record)
    }

    private fun fromDayStart(type: Int, snapshot: Snapshot): HealthRecord? {
        if (snapshot.total <= 0.0) return null
        val start = minOf(snapshot.dayStartSec, snapshot.endSec)
        return HealthRecord(type, start, snapshot.endSec, snapshot.total)
    }
}
