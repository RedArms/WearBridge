package com.wearos.ancsbridge.health

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.clearPassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.IntervalDataPoint
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.data.UserActivityInfo
import androidx.health.services.client.data.UserActivityState
import androidx.health.services.client.getCapabilities
import androidx.health.services.client.setPassiveListenerService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * Passive health monitoring through Health Services, plus the local queue of
 * records waiting to be sent to the iPhone (see [HealthSyncManager]).
 *
 * Collected passively, when the watch supports them and the user granted access:
 * heart rate, steps, distance, floors and asleep periods. Sleep comes from the
 * "asleep" user activity state, so it has no stages (deep, REM…).
 */
object HealthMonitoring {

    private const val TAG = "HealthMonitoring"
    private const val PREFS = "health_sync"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ASLEEP_SINCE = "asleep_since"
    private const val KEY_DAILY_PREFIX = "daily_total_"
    // When the phone relay last sent sleep / heart rate. While it does, the watch
    // leaves those to it, so Apple Health doesn't get two overlapping copies.
    private const val KEY_RELAY_SLEEP_LAST_SEEN = "relay_sleep_last_seen"
    private const val KEY_RELAY_HEART_RATE_LAST_SEEN = "relay_heart_rate_last_seen"

    /**
     * How long the relay counts as sending a type after its last batch of it. The relay
     * re-reads from 36 h before its last success, so it fills the gap when it comes back.
     */
    private const val RELAY_ACTIVE_WINDOW_MS = 3 * 24 * 3600 * 1000L
    private const val QUEUE_FILE = "health_queue.bin"
    private const val MAX_PLAUSIBLE_BPM = 300.0

    // Wear OS 6 (API 36) moves body sensor access to granular health permissions.
    // Constants are spelled out because compileSdk is 35.
    private const val API_36 = 36
    private const val PERMISSION_READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"
    private const val PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND =
        "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

    /** Interval data types: the delta type when the watch offers it passively, else the daily total. */
    private val INTERVAL_TYPES = listOf(
        DataType.STEPS to DataType.STEPS_DAILY,
        DataType.DISTANCE to DataType.DISTANCE_DAILY,
        DataType.FLOORS to DataType.FLOORS_DAILY,
    )

    private val _newData = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Emits when records were queued, so the sync can run soon. */
    val newData: SharedFlow<Unit> = _newData.asSharedFlow()

    @Volatile
    private var store: HealthRecordStore? = null

    fun store(context: Context): HealthRecordStore =
        store ?: synchronized(this) {
            store ?: HealthRecordStore(File(context.applicationContext.filesDir, QUEUE_FILE)).also { store = it }
        }

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun pendingCount(context: Context): Int = store(context).size()

    // --- Permissions ---

    /** Request these together from an activity. */
    fun foregroundPermissions(): Array<String> = buildList {
        add(Manifest.permission.ACTIVITY_RECOGNITION)
        add(Manifest.permission.BODY_SENSORS)
        if (Build.VERSION.SDK_INT >= API_36) add(PERMISSION_READ_HEART_RATE)
    }.toTypedArray()

    /** Background heart rate access. Android requires asking for it separately, after [foregroundPermissions]. */
    fun backgroundPermissions(): Array<String> = buildList {
        add(Manifest.permission.BODY_SENSORS_BACKGROUND)
        if (Build.VERSION.SDK_INT >= API_36) add(PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
    }.toTypedArray()

    fun hasActivityPermission(context: Context): Boolean =
        granted(context, Manifest.permission.ACTIVITY_RECOGNITION)

    fun hasHeartRatePermission(context: Context): Boolean =
        granted(context, Manifest.permission.BODY_SENSORS) || granted(context, PERMISSION_READ_HEART_RATE)

    fun hasBackgroundHeartRatePermission(context: Context): Boolean =
        granted(context, Manifest.permission.BODY_SENSORS_BACKGROUND) ||
            granted(context, PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)

    // --- Registration ---

    /** Register passive monitoring and remember the choice. Returns false if nothing could be monitored. */
    suspend fun enable(context: Context): Boolean {
        val registered = register(context)
        prefs(context).edit().putBoolean(KEY_ENABLED, registered).apply()
        return registered
    }

    /** Stop monitoring and drop anything not yet sent. */
    suspend fun disable(context: Context) {
        prefs(context).edit().putBoolean(KEY_ENABLED, false).remove(KEY_ASLEEP_SINCE).apply()
        store(context).clear()
        try {
            HealthServices.getClient(context).passiveMonitoringClient.clearPassiveListenerService()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear passive listener", e)
        }
    }

    /** Health Services registrations don't survive a reboot, so this runs on every service start. */
    suspend fun registerIfEnabled(context: Context) {
        if (isEnabled(context)) register(context)
    }

    private suspend fun register(context: Context): Boolean {
        val client = HealthServices.getClient(context).passiveMonitoringClient
        return try {
            val capabilities = client.getCapabilities()
            val supported = capabilities.supportedDataTypesPassiveMonitoring
            val dataTypes = mutableSetOf<DataType<*, *>>()

            // Heart rate is only useful in the background, so require that access too.
            if (hasHeartRatePermission(context) && hasBackgroundHeartRatePermission(context) &&
                DataType.HEART_RATE_BPM in supported
            ) {
                dataTypes += DataType.HEART_RATE_BPM
            }

            val activityAllowed = hasActivityPermission(context)
            if (activityAllowed) {
                for ((delta, daily) in INTERVAL_TYPES) {
                    when {
                        delta in supported -> dataTypes += delta
                        daily in supported -> dataTypes += daily
                    }
                }
            }

            val sleep = activityAllowed && capabilities.supportedUserActivityStates.any {
                it.id == UserActivityState.USER_ACTIVITY_ASLEEP.id
            }

            if (dataTypes.isEmpty() && !sleep) {
                Log.w(TAG, "Nothing to monitor (permissions missing or unsupported on this watch)")
                return false
            }

            val config = PassiveListenerConfig.builder()
                .setDataTypes(dataTypes)
                .setShouldUserActivityInfoBeRequested(sleep)
                .build()
            client.setPassiveListenerService(HealthPassiveListenerService::class.java, config)
            Log.i(TAG, "Passive monitoring registered: ${dataTypes.map { it.name }}, sleep=$sleep")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Passive monitoring registration failed", e)
            false
        }
    }

    // --- Incoming data (from HealthPassiveListenerService) ---

    fun onDataPoints(context: Context, container: DataPointContainer) {
        val boot = Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())
        val records = mutableListOf<HealthRecord>()

        // Samsung Health's heart rate comes through the relay when it runs.
        if (!isRelaySending(context, KEY_RELAY_HEART_RATE_LAST_SEEN)) {
            for (point in container.getData(DataType.HEART_RATE_BPM)) {
                val bpm = point.value
                if (bpm > 0.0 && bpm < MAX_PLAUSIBLE_BPM) {
                    val time = point.getTimeInstant(boot).epochSecond
                    records += HealthRecord(HealthProtocol.TYPE_HEART_RATE, time, time, bpm)
                }
            }
        }

        addIntervals(records, HealthProtocol.TYPE_STEPS, container.getData(DataType.STEPS), boot)
        addIntervals(records, HealthProtocol.TYPE_DISTANCE, container.getData(DataType.DISTANCE), boot)
        addIntervals(records, HealthProtocol.TYPE_FLOORS, container.getData(DataType.FLOORS), boot)

        synchronized(this) {
            val prefs = prefs(context)
            addDailyTotals(prefs, records, HealthProtocol.TYPE_STEPS, container.getData(DataType.STEPS_DAILY), boot)
            addDailyTotals(prefs, records, HealthProtocol.TYPE_DISTANCE, container.getData(DataType.DISTANCE_DAILY), boot)
            addDailyTotals(prefs, records, HealthProtocol.TYPE_FLOORS, container.getData(DataType.FLOORS_DAILY), boot)
        }

        queue(context, records)
    }

    fun onUserActivity(context: Context, info: UserActivityInfo) {
        val asleep = info.userActivityState.id == UserActivityState.USER_ACTIVITY_ASLEEP.id
        val at = info.stateChangeTime.epochSecond
        val session = synchronized(this) {
            val prefs = prefs(context)
            val since = prefs.getLong(KEY_ASLEEP_SINCE, -1L).takeIf { it >= 0L }
            val update = SleepSessions.onStateChange(since, asleep, at)
            val editor = prefs.edit()
            val newSince = update.asleepSinceEpochSec
            if (newSince != null) editor.putLong(KEY_ASLEEP_SINCE, newSince) else editor.remove(KEY_ASLEEP_SINCE)
            editor.apply()
            update.session
        }
        if (session != null) {
            if (isRelaySending(context, KEY_RELAY_SLEEP_LAST_SEEN)) {
                // Samsung Health's staged sleep comes through the relay; don't add a second, coarser copy.
                Log.i(TAG, "Sleep session ${session.startEpochSec}..${session.endEpochSec} skipped (relay active)")
            } else {
                Log.i(TAG, "Sleep session ${session.startEpochSec}..${session.endEpochSec}")
                queue(context, listOf(session))
            }
        }
    }

    /** Records sent by the phone relay (Samsung Health), see HealthRelayListenerService. */
    fun onRelayRecords(context: Context, records: List<HealthRecord>) {
        if (records.isEmpty()) return
        val now = System.currentTimeMillis()
        val editor = prefs(context).edit()
        if (records.any { it.type == HealthProtocol.TYPE_IN_BED || it.type == HealthProtocol.TYPE_SLEEP }) {
            editor.putLong(KEY_RELAY_SLEEP_LAST_SEEN, now)
        }
        if (records.any { it.type == HealthProtocol.TYPE_HEART_RATE }) {
            editor.putLong(KEY_RELAY_HEART_RATE_LAST_SEEN, now)
        }
        editor.apply()
        // Keep them even while Health Sync is off: the relay counts them as delivered
        // and won't resend for a day. They are sent once sync is on (turning it off clears the queue).
        append(context, records)
    }

    private fun isRelaySending(context: Context, key: String): Boolean {
        val lastSeen = prefs(context).getLong(key, 0L)
        return lastSeen > 0L && System.currentTimeMillis() - lastSeen < RELAY_ACTIVE_WINDOW_MS
    }

    fun onPermissionLost(context: Context) {
        Log.w(TAG, "Health permission revoked; passive monitoring stopped")
        prefs(context).edit().putBoolean(KEY_ENABLED, false).apply()
    }

    private fun queue(context: Context, records: List<HealthRecord>) {
        if (records.isEmpty() || !isEnabled(context)) return
        append(context, records)
    }

    private fun append(context: Context, records: List<HealthRecord>) {
        try {
            store(context).append(records)
        } catch (e: Exception) {
            // A full disk must not take the whole app (and ANCS) down with it.
            Log.e(TAG, "Couldn't queue ${records.size} health records", e)
            return
        }
        _newData.tryEmit(Unit)
    }

    private fun <T : Number> addIntervals(
        out: MutableList<HealthRecord>,
        type: Int,
        points: List<IntervalDataPoint<T>>,
        boot: Instant
    ) {
        for (point in points) {
            val value = point.value.toDouble()
            if (value > 0.0) {
                out += HealthRecord(
                    type,
                    point.getStartInstant(boot).epochSecond,
                    point.getEndInstant(boot).epochSecond,
                    value
                )
            }
        }
    }

    private fun <T : Number> addDailyTotals(
        prefs: SharedPreferences,
        out: MutableList<HealthRecord>,
        type: Int,
        points: List<IntervalDataPoint<T>>,
        boot: Instant
    ) {
        if (points.isEmpty()) return
        val zone = ZoneId.systemDefault()
        val key = KEY_DAILY_PREFIX + type
        var snapshot = parseSnapshot(prefs.getString(key, null))
        for (point in points.sortedBy { it.endDurationFromBoot }) {
            val end = point.getEndInstant(boot)
            val day = end.atZone(zone).toLocalDate()
            val current = DailyTotals.Snapshot(
                dayKey = day.toEpochDay(),
                dayStartSec = day.atStartOfDay(zone).toEpochSecond(),
                endSec = end.epochSecond,
                total = point.value.toDouble()
            )
            val result = DailyTotals.delta(type, snapshot, current)
            snapshot = result.snapshot
            result.record?.let { out += it }
        }
        snapshot?.let { prefs.edit().putString(key, formatSnapshot(it)).apply() }
    }

    private fun formatSnapshot(s: DailyTotals.Snapshot) = "${s.dayKey};${s.dayStartSec};${s.endSec};${s.total}"

    private fun parseSnapshot(raw: String?): DailyTotals.Snapshot? {
        val parts = raw?.split(';') ?: return null
        if (parts.size != 4) return null
        return DailyTotals.Snapshot(
            dayKey = parts[0].toLongOrNull() ?: return null,
            dayStartSec = parts[1].toLongOrNull() ?: return null,
            endSec = parts[2].toLongOrNull() ?: return null,
            total = parts[3].toDoubleOrNull() ?: return null
        )
    }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
