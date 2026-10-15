package com.wearos.ancsbridge.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.wearos.ancsbridge.health.HealthMonitoring
import com.wearos.ancsbridge.ui.theme.AncsBridgeTheme
import com.wearos.ancsbridge.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            viewModel.startService()
        }
    }

    // Health permissions are opt-in and never block notifications.
    private val healthPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onHealthForegroundPermissionsResult()
    }

    // Android only grants background body sensor access in a separate request.
    private val healthBackgroundPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshHealthSync()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestPermissionsIfNeeded()

        setContent {
            AncsBridgeTheme {
                MainScreen(
                    viewModel = viewModel,
                    onToggleHealthSync = { toggleHealthSync() }
                )
            }
        }
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.POST_NOTIFICATIONS,
        )

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isEmpty()) {
            viewModel.startService()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun toggleHealthSync() {
        if (viewModel.healthSyncEnabled.value) {
            viewModel.setHealthSyncEnabled(false)
            return
        }
        val missing = HealthMonitoring.foregroundPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            onHealthForegroundPermissionsResult()
        } else {
            healthPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun onHealthForegroundPermissionsResult() {
        val activity = HealthMonitoring.hasActivityPermission(this)
        val heartRate = HealthMonitoring.hasHeartRatePermission(this)
        if (!activity && !heartRate) {
            viewModel.onHealthPermissionsDenied()
            return
        }
        viewModel.setHealthSyncEnabled(true)
        if (heartRate && !HealthMonitoring.hasBackgroundHeartRatePermission(this)) {
            healthBackgroundPermissionLauncher.launch(HealthMonitoring.backgroundPermissions())
        }
    }
}
