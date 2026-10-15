package com.wearos.ancsbridge.health

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One health measurement to mirror into Apple Health.
 *
 * Instantaneous samples (e.g. heart rate from the watch) use
 * `startEpochSec == endEpochSec`. Interval quantities (steps, distance, floors)
 * carry the amount for the interval. See [HealthProtocol] for what [value]
 * means for each type.
 */
data class HealthRecord(
    val type: Int,
    val startEpochSec: Long,
    val endEpochSec: Long,
    val value: Double
)

/**
 * Wire format for the Health Data characteristic of the companion service.
 *
 * The watch writes batches to the iPhone companion app with write-with-response.
 * All integers are little-endian, matching the ANCS conventions used elsewhere.
 *
 * ```
 * Batch  := version:u8 (=1) | count:u8 (1..255) | Record × count
 * Record := type:u8 | start:u32 (Unix s) | end:u32 (Unix s) | value:f64     (17 bytes)
 * ```
 *
 * The same batches carry relay data from the phone to the watch over the Wear OS
 * Data Layer (see `:relay`). The iOS side mirrors this in `HealthProtocol.swift`.
 * Keep both in sync; new record types must be added on both sides.
 */
object HealthProtocol {

    const val VERSION = 1
    const val HEADER_SIZE = 2
    const val RECORD_SIZE = 17

    /** Wear OS Data Layer message path for batches sent by the phone relay to the watch. */
    const val RELAY_MESSAGE_PATH = "/wearbridge/health/batch"

    /** ATT caps an attribute value at 512 bytes. */
    const val MAX_ATTRIBUTE_SIZE = 512
    const val MAX_RECORDS_PER_BATCH = 255

    private const val MAX_UINT32 = 0xFFFF_FFFFL

    const val TYPE_HEART_RATE = 1        // beats per minute
    const val TYPE_STEPS = 2             // count over the interval
    const val TYPE_DISTANCE = 3          // meters over the interval
    const val TYPE_FLOORS = 4            // floors climbed over the interval
    const val TYPE_SLEEP = 5             // sleep interval, value = SLEEP_STAGE_*
    const val TYPE_IN_BED = 6            // whole sleep session, value = sleep score 0..100 (0 = none)
    const val TYPE_OXYGEN_SATURATION = 7 // SpO2 in percent (0..100]
    const val TYPE_BLOOD_PRESSURE = 8    // value = systolic * 1000 + diastolic, mmHg (see bloodPressureValue)

    const val SLEEP_STAGE_UNSPECIFIED = 0 // asleep, stage unknown (watch "asleep" state)
    const val SLEEP_STAGE_AWAKE = 1
    const val SLEEP_STAGE_LIGHT = 2
    const val SLEEP_STAGE_DEEP = 3
    const val SLEEP_STAGE_REM = 4

    val KNOWN_TYPES = setOf(
        TYPE_HEART_RATE, TYPE_STEPS, TYPE_DISTANCE, TYPE_FLOORS,
        TYPE_SLEEP, TYPE_IN_BED, TYPE_OXYGEN_SATURATION, TYPE_BLOOD_PRESSURE
    )

    fun isValid(record: HealthRecord): Boolean {
        val value = record.value
        val common = record.type in KNOWN_TYPES &&
            record.startEpochSec in 0..MAX_UINT32 &&
            record.endEpochSec in record.startEpochSec..MAX_UINT32 &&
            value.isFinite() &&
            value >= 0.0
        if (!common) return false
        return when (record.type) {
            TYPE_SLEEP -> isWhole(value) && value <= SLEEP_STAGE_REM
            TYPE_IN_BED -> isWhole(value) && value <= 100.0
            TYPE_OXYGEN_SATURATION -> value > 0.0 && value <= 100.0
            TYPE_BLOOD_PRESSURE -> isWhole(value) && splitBloodPressure(value).let { (sys, dia) ->
                sys in 1..999 && dia in 1..999
            }
            else -> true
        }
    }

    /** Packs a blood pressure reading into one record value (rounded to whole mmHg). */
    fun bloodPressureValue(systolicMmHg: Double, diastolicMmHg: Double): Double =
        Math.round(systolicMmHg) * 1000.0 + Math.round(diastolicMmHg)

    /** (systolic, diastolic) in mmHg from a [TYPE_BLOOD_PRESSURE] value. */
    fun splitBloodPressure(value: Double): Pair<Int, Int> {
        val packed = value.toLong()
        return (packed / 1000).toInt() to (packed % 1000).toInt()
    }

    private fun isWhole(value: Double) = value == Math.floor(value)

    /**
     * Largest batch that fits in a single write without falling back to
     * prepared (long) writes, given the negotiated ATT MTU.
     */
    fun maxRecordsPerWrite(mtu: Int): Int {
        val payload = minOf(mtu - 3, MAX_ATTRIBUTE_SIZE)
        return ((payload - HEADER_SIZE) / RECORD_SIZE).coerceIn(1, MAX_RECORDS_PER_BATCH)
    }

    /** Encode records back to back, without a batch header (used by the on-disk queue). */
    fun encodeRecords(records: List<HealthRecord>): ByteArray {
        val buffer = ByteBuffer.allocate(records.size * RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        records.forEach { putRecord(buffer, it) }
        return buffer.array()
    }

    /** Decode [count] records starting at [offset]. Caller guarantees the bytes are there. */
    fun decodeRecords(bytes: ByteArray, offset: Int = 0, count: Int = (bytes.size - offset) / RECORD_SIZE): List<HealthRecord> {
        val buffer = ByteBuffer.wrap(bytes, offset, count * RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        return List(count) { getRecord(buffer) }
    }

    fun encodeBatch(records: List<HealthRecord>): ByteArray {
        require(records.size in 1..MAX_RECORDS_PER_BATCH) { "Batch must hold 1..$MAX_RECORDS_PER_BATCH records" }
        val buffer = ByteBuffer.allocate(HEADER_SIZE + records.size * RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(VERSION.toByte())
        buffer.put(records.size.toByte())
        records.forEach { putRecord(buffer, it) }
        return buffer.array()
    }

    /** Returns null for anything that is not a well-formed version-1 batch. */
    fun decodeBatch(payload: ByteArray): List<HealthRecord>? {
        if (payload.size < HEADER_SIZE) return null
        val version = payload[0].toInt() and 0xFF
        val count = payload[1].toInt() and 0xFF
        if (version != VERSION || count == 0) return null
        if (payload.size != HEADER_SIZE + count * RECORD_SIZE) return null
        return decodeRecords(payload, HEADER_SIZE, count)
    }

    private fun putRecord(buffer: ByteBuffer, record: HealthRecord) {
        require(isValid(record)) { "Invalid health record: $record" }
        buffer.put(record.type.toByte())
        buffer.putInt(record.startEpochSec.toInt()) // low 32 bits, read back as unsigned
        buffer.putInt(record.endEpochSec.toInt())
        buffer.putDouble(record.value)
    }

    private fun getRecord(buffer: ByteBuffer): HealthRecord {
        val type = buffer.get().toInt() and 0xFF
        val start = buffer.getInt().toLong() and MAX_UINT32
        val end = buffer.getInt().toLong() and MAX_UINT32
        val value = buffer.getDouble()
        return HealthRecord(type, start, end, value)
    }
}
