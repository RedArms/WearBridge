package com.wearos.ancsbridge.relay

import android.app.Activity
import android.content.Context
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.data.HealthDataPoint
import com.samsung.android.sdk.health.data.error.ErrorCode
import com.samsung.android.sdk.health.data.error.HealthDataException
import com.samsung.android.sdk.health.data.error.ResolvablePlatformException
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.permission.Permission
import com.samsung.android.sdk.health.data.request.DataType.BloodOxygenType
import com.samsung.android.sdk.health.data.request.DataType.BloodPressureType
import com.samsung.android.sdk.health.data.request.DataType.HeartRateType
import com.samsung.android.sdk.health.data.request.DataType.SleepType
import com.samsung.android.sdk.health.data.request.DataTypes
import com.samsung.android.sdk.health.data.request.InstantTimeFilter
import com.samsung.android.sdk.health.data.request.Ordering
import com.wearos.ancsbridge.health.HealthProtocol
import com.wearos.ancsbridge.health.HealthRecord
import com.wearos.ancsbridge.relay.core.BloodPressureReading
import com.wearos.ancsbridge.relay.core.SamsungMapping
import com.wearos.ancsbridge.relay.core.SleepNight
import com.wearos.ancsbridge.relay.core.SleepSessionData
import com.wearos.ancsbridge.relay.core.StageSpan
import com.wearos.ancsbridge.relay.core.TimedValue
import java.time.Instant

/**
 * Reads what the Galaxy Watch synced into Samsung Health on this phone, through
 * Samsung's Health Data SDK. For personal use the SDK works in Samsung Health's
 * developer mode, without partner registration (read-only).
 */
class SamsungHealthReader(context: Context) {

    companion object {
        private val SLEEP = Permission.of(DataTypes.SLEEP, AccessType.READ)
        private val BLOOD_OXYGEN = Permission.of(DataTypes.BLOOD_OXYGEN, AccessType.READ)
        private val BLOOD_PRESSURE = Permission.of(DataTypes.BLOOD_PRESSURE, AccessType.READ)
        private val HEART_RATE = Permission.of(DataTypes.HEART_RATE, AccessType.READ)

        val PERMISSIONS: Set<Permission> = setOf(SLEEP, BLOOD_OXYGEN, BLOOD_PRESSURE, HEART_RATE)

        /** A user-facing explanation for SDK errors, or null for anything else. */
        fun explain(error: Throwable): String? = when {
            error !is HealthDataException -> null
            // 2003 "Could not get policy": the app isn't registered with Samsung,
            // which is expected; developer mode is what lets it read anyway.
            error.errorCode == ErrorCode.ERR_ACCESS_CONTROL ||
                error.javaClass.simpleName == "AuthorizationException" ->
                "Samsung Health developer mode is off: Settings → About Samsung Health → " +
                    "tap the version 10 times → Developer mode (Samsung Health Data SDK) → on."
            error.errorCode == ErrorCode.ERR_NO_USER_PERMISSION ->
                "Samsung Health access not granted. Tap \"Grant Samsung Health access\"."
            error is ResolvablePlatformException ->
                "Samsung Health needs attention (install, update or accept its terms). Open this app and tap Grant."
            else -> "Samsung Health error ${error.errorCode}: ${error.errorMessage ?: error.message}"
        }
    }

    private val store = HealthDataService.getStore(context.applicationContext)

    suspend fun grantedPermissions(): Set<Permission> = store.getGrantedPermissions(PERMISSIONS)

    /** Shows Samsung Health's permission screen (or its install/update/terms screen first). */
    suspend fun requestPermissions(activity: Activity) {
        try {
            store.requestPermissions(PERMISSIONS, activity)
        } catch (e: ResolvablePlatformException) {
            if (e.hasResolution) e.resolve(activity) else throw e
        }
    }

    /** Everything readable in [from]..[to], as Health Data Bridge records. */
    suspend fun read(from: Instant, to: Instant): List<HealthRecord> {
        val granted = grantedPermissions()
        val filter = InstantTimeFilter.of(from, to)
        val records = mutableListOf<HealthRecord>()

        if (SLEEP in granted) {
            val request = DataTypes.SLEEP.readDataRequestBuilder
                .setInstantTimeFilter(filter)
                .setOrdering(Ordering.ASC)
                .build()
            records += SamsungMapping.sleep(store.readData(request).dataList.map(::toSleepNight))
        }

        if (BLOOD_OXYGEN in granted) {
            val request = DataTypes.BLOOD_OXYGEN.readDataRequestBuilder
                .setInstantTimeFilter(filter)
                .setOrdering(Ordering.ASC)
                .build()
            records += SamsungMapping.oxygenSaturation(store.readData(request).dataList.map { point ->
                TimedValue(point.startTime, point.endTime, point.getValue(BloodOxygenType.OXYGEN_SATURATION))
            })
        }

        if (BLOOD_PRESSURE in granted) {
            val request = DataTypes.BLOOD_PRESSURE.readDataRequestBuilder
                .setInstantTimeFilter(filter)
                .setOrdering(Ordering.ASC)
                .build()
            records += SamsungMapping.bloodPressure(store.readData(request).dataList.map { point ->
                BloodPressureReading(
                    time = point.startTime,
                    systolic = point.getValue(BloodPressureType.SYSTOLIC),
                    diastolic = point.getValue(BloodPressureType.DIASTOLIC),
                    pulse = point.getValue(BloodPressureType.PULSE_RATE)
                )
            })
        }

        if (HEART_RATE in granted) {
            val request = DataTypes.HEART_RATE.readDataRequestBuilder
                .setInstantTimeFilter(filter)
                .setOrdering(Ordering.ASC)
                .build()
            records += SamsungMapping.heartRate(store.readData(request).dataList.map { point ->
                TimedValue(point.startTime, point.endTime, point.getValue(HeartRateType.HEART_RATE))
            })
        }

        return records
    }

    private fun toSleepNight(point: HealthDataPoint): SleepNight {
        val sessions = point.getValue(SleepType.SESSIONS).orEmpty().map { session ->
            SleepSessionData(
                start = session.startTime,
                end = session.endTime,
                stages = session.stages.orEmpty().map { stage ->
                    StageSpan(stage.startTime, stage.endTime, stageCode(stage.stage))
                }
            )
        }
        return SleepNight(
            start = point.startTime,
            end = point.endTime,
            score = point.getValue(SleepType.SLEEP_SCORE),
            sessions = sessions
        )
    }

    private fun stageCode(stage: SleepType.StageType): Int = when (stage) {
        SleepType.StageType.AWAKE -> HealthProtocol.SLEEP_STAGE_AWAKE
        SleepType.StageType.LIGHT -> HealthProtocol.SLEEP_STAGE_LIGHT
        SleepType.StageType.DEEP -> HealthProtocol.SLEEP_STAGE_DEEP
        SleepType.StageType.REM -> HealthProtocol.SLEEP_STAGE_REM
        else -> HealthProtocol.SLEEP_STAGE_UNSPECIFIED
    }
}
