package com.wearos.ancsbridge.relay.core

import com.wearos.ancsbridge.health.HealthProtocol
import com.wearos.ancsbridge.health.HealthRecord
import java.time.Instant

// Plain snapshots of Samsung Health data, so the mapping below has no SDK dependency.

/** One Samsung Health sleep record (usually one per night) with its sessions. */
data class SleepNight(
    val start: Instant,
    val end: Instant?,
    val score: Int?,
    val sessions: List<SleepSessionData>
)

data class SleepSessionData(val start: Instant, val end: Instant, val stages: List<StageSpan>)

/** [stage] is one of the HealthProtocol.SLEEP_STAGE_* codes. */
data class StageSpan(val start: Instant, val end: Instant, val stage: Int)

data class TimedValue(val start: Instant, val end: Instant?, val value: Float?)

data class BloodPressureReading(val time: Instant, val systolic: Float?, val diastolic: Float?, val pulse: Int?)

/** Samsung Health data → Health Data Bridge records. */
object SamsungMapping {

    fun sleep(nights: List<SleepNight>): List<HealthRecord> = buildList {
        for (night in nights) {
            val score = (night.score ?: 0).coerceIn(0, 100).toDouble()
            val sessions = night.sessions.ifEmpty {
                // No session detail: treat the whole record as one session without stages.
                night.end?.let { listOf(SleepSessionData(night.start, it, emptyList())) } ?: emptyList()
            }
            for (session in sessions) {
                val start = session.start.epochSecond
                val end = session.end.epochSecond
                if (end <= start) continue
                add(HealthRecord(HealthProtocol.TYPE_IN_BED, start, end, score))
                val stages = session.stages.filter { it.end.epochSecond > it.start.epochSecond }
                if (stages.isEmpty()) {
                    add(HealthRecord(HealthProtocol.TYPE_SLEEP, start, end, HealthProtocol.SLEEP_STAGE_UNSPECIFIED.toDouble()))
                } else {
                    stages.forEach {
                        add(HealthRecord(HealthProtocol.TYPE_SLEEP, it.start.epochSecond, it.end.epochSecond, it.stage.toDouble()))
                    }
                }
            }
        }
    }

    fun oxygenSaturation(samples: List<TimedValue>): List<HealthRecord> =
        samples.mapNotNull { timed(HealthProtocol.TYPE_OXYGEN_SATURATION, it) }

    fun heartRate(samples: List<TimedValue>): List<HealthRecord> =
        samples.mapNotNull { timed(HealthProtocol.TYPE_HEART_RATE, it) }

    /** Each reading becomes a blood pressure record, plus a heart rate record for its pulse. */
    fun bloodPressure(readings: List<BloodPressureReading>): List<HealthRecord> = buildList {
        for (reading in readings) {
            val time = reading.time.epochSecond
            val systolic = reading.systolic ?: continue
            val diastolic = reading.diastolic ?: continue
            val value = HealthProtocol.bloodPressureValue(systolic.toDouble(), diastolic.toDouble())
            addIfValid(HealthRecord(HealthProtocol.TYPE_BLOOD_PRESSURE, time, time, value))
            reading.pulse?.takeIf { it > 0 }?.let {
                addIfValid(HealthRecord(HealthProtocol.TYPE_HEART_RATE, time, time, it.toDouble()))
            }
        }
    }

    private fun timed(type: Int, sample: TimedValue): HealthRecord? {
        val value = sample.value?.toDouble() ?: return null
        val start = sample.start.epochSecond
        val end = maxOf(start, sample.end?.epochSecond ?: start)
        return HealthRecord(type, start, end, value).takeIf(HealthProtocol::isValid)
    }

    private fun MutableList<HealthRecord>.addIfValid(record: HealthRecord) {
        if (HealthProtocol.isValid(record)) add(record)
    }
}
