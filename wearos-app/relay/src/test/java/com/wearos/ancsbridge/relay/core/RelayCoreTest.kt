package com.wearos.ancsbridge.relay.core

import com.wearos.ancsbridge.health.HealthProtocol
import com.wearos.ancsbridge.health.HealthRecord
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

class RelayCoreTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("relay").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun t(sec: Long) = Instant.ofEpochSecond(sec)

    // --- Sleep ---

    @Test
    fun `staged night becomes in-bed plus stage records`() {
        val night = SleepNight(
            start = t(1_000), end = t(1_000 + 8 * 3600), score = 82,
            sessions = listOf(
                SleepSessionData(
                    t(1_000), t(1_000 + 8 * 3600),
                    listOf(
                        StageSpan(t(1_000), t(2_800), HealthProtocol.SLEEP_STAGE_LIGHT),
                        StageSpan(t(2_800), t(5_200), HealthProtocol.SLEEP_STAGE_DEEP),
                        StageSpan(t(5_200), t(5_200), HealthProtocol.SLEEP_STAGE_REM), // empty span, dropped
                    )
                )
            )
        )
        assertEquals(
            listOf(
                HealthRecord(HealthProtocol.TYPE_IN_BED, 1_000, 1_000 + 8 * 3600, 82.0),
                HealthRecord(HealthProtocol.TYPE_SLEEP, 1_000, 2_800, HealthProtocol.SLEEP_STAGE_LIGHT.toDouble()),
                HealthRecord(HealthProtocol.TYPE_SLEEP, 2_800, 5_200, HealthProtocol.SLEEP_STAGE_DEEP.toDouble()),
            ),
            SamsungMapping.sleep(listOf(night))
        )
    }

    @Test
    fun `night without sessions or stages is still recorded`() {
        val night = SleepNight(t(1_000), t(9_000), null, emptyList())
        assertEquals(
            listOf(
                HealthRecord(HealthProtocol.TYPE_IN_BED, 1_000, 9_000, 0.0),
                HealthRecord(HealthProtocol.TYPE_SLEEP, 1_000, 9_000, HealthProtocol.SLEEP_STAGE_UNSPECIFIED.toDouble()),
            ),
            SamsungMapping.sleep(listOf(night))
        )
        assertTrue(SamsungMapping.sleep(listOf(SleepNight(t(1_000), null, 70, emptyList()))).isEmpty())
    }

    // --- Measurements ---

    @Test
    fun `oxygen and heart rate keep valid samples only`() {
        val spo2 = SamsungMapping.oxygenSaturation(
            listOf(TimedValue(t(100), t(160), 96f), TimedValue(t(200), null, 0f), TimedValue(t(300), null, null))
        )
        assertEquals(listOf(HealthRecord(HealthProtocol.TYPE_OXYGEN_SATURATION, 100, 160, 96.0)), spo2)

        val hr = SamsungMapping.heartRate(listOf(TimedValue(t(100), t(50), 64f)))
        assertEquals(listOf(HealthRecord(HealthProtocol.TYPE_HEART_RATE, 100, 100, 64.0)), hr)
    }

    @Test
    fun `blood pressure includes the pulse`() {
        val records = SamsungMapping.bloodPressure(
            listOf(
                BloodPressureReading(t(500), 118.6f, 76.2f, 61),
                BloodPressureReading(t(600), null, 80f, 70), // incomplete, skipped
            )
        )
        assertEquals(
            listOf(
                HealthRecord(HealthProtocol.TYPE_BLOOD_PRESSURE, 500, 500, 119_076.0),
                HealthRecord(HealthProtocol.TYPE_HEART_RATE, 500, 500, 61.0),
            ),
            records
        )
    }

    // --- Bookkeeping ---

    @Test
    fun `sync window backfills then overlaps`() {
        val now = 10_000_000L
        assertEquals(now - SyncWindow.FIRST_RUN_LOOKBACK_SEC, SyncWindow.startEpochSec(now, null))
        assertEquals(now - 3600 - SyncWindow.OVERLAP_SEC, SyncWindow.startEpochSec(now, now - 3600))
        assertEquals(now - SyncWindow.OVERLAP_SEC, SyncWindow.startEpochSec(now, now + 60)) // clock went back
    }

    @Test
    fun `sync window reaches back to the newest record after the watch was away`() {
        val now = 10_000_000L
        val lastSuccess = now - 900
        // Watch synced 5 min ago: the usual overlap.
        assertEquals(lastSuccess - SyncWindow.OVERLAP_SEC, SyncWindow.startEpochSec(now, lastSuccess, now - 300))
        // Nothing new for 60 h: read from just before the last record, which the watch fills in when back.
        val departed = now - 60 * 3600
        assertEquals(departed - SyncWindow.NEWEST_RECORD_MARGIN_SEC, SyncWindow.startEpochSec(now, lastSuccess, departed))
        // ...but not further back than the late-sync limit.
        assertEquals(now - SyncWindow.MAX_LATE_SYNC_SEC, SyncWindow.startEpochSec(now, lastSuccess, now - 40 * 24 * 3600))
        // An old last success still wins (the relay itself didn't run).
        assertEquals(now - 20 * 24 * 3600 - SyncWindow.OVERLAP_SEC,
            SyncWindow.startEpochSec(now, now - 20 * 24 * 3600, now - 40 * 24 * 3600))
    }

    @Test
    fun `sent log outlives the longest read window`() {
        assertTrue(SentLog.DEFAULT_TTL_SEC > SyncWindow.MAX_LATE_SYNC_SEC + SyncWindow.OVERLAP_SEC)
    }

    @Test
    fun `sent log skips recent records and expires them`() {
        val log = SentLog(File(dir, "sent.txt"), ttlSec = 100)
        val a = HealthRecord(HealthProtocol.TYPE_HEART_RATE, 1, 1, 60.0)
        val b = HealthRecord(HealthProtocol.TYPE_HEART_RATE, 2, 2, 61.0)

        assertEquals(listOf(a, b), log.filterUnsent(listOf(a, b, a), nowEpochSec = 1_000))
        log.markSent(listOf(a), nowEpochSec = 1_000)

        assertEquals(listOf(b), log.filterUnsent(listOf(a, b), nowEpochSec = 1_050))
        assertEquals(listOf(a, b), log.filterUnsent(listOf(a, b), nowEpochSec = 1_100)) // expired
    }

    @Test
    fun `changed value counts as new`() {
        val log = SentLog(File(dir, "sent.txt"))
        val night = HealthRecord(HealthProtocol.TYPE_IN_BED, 1, 2, 70.0)
        log.markSent(listOf(night), 10)
        assertEquals(listOf(night.copy(value = 75.0)), log.filterUnsent(listOf(night.copy(value = 75.0)), 20))
    }
}
