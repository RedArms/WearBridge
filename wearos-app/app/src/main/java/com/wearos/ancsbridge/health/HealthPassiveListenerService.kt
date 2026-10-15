package com.wearos.ancsbridge.health

import android.util.Log
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.UserActivityInfo

/**
 * Receives batched passive data from Health Services, even when WearBridge is
 * not in the foreground, and queues it for the iPhone.
 */
class HealthPassiveListenerService : PassiveListenerService() {

    companion object {
        private const val TAG = "HealthPassiveListener"
    }

    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        Log.d(TAG, "Passive data: ${dataPoints.dataTypes.joinToString { it.name }}")
        HealthMonitoring.onDataPoints(this, dataPoints)
    }

    override fun onUserActivityInfoReceived(info: UserActivityInfo) {
        Log.d(TAG, "User activity: ${info.userActivityState.name} at ${info.stateChangeTime}")
        HealthMonitoring.onUserActivity(this, info)
    }

    override fun onPermissionLost() {
        HealthMonitoring.onPermissionLost(this)
    }
}
