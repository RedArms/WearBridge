package com.wearos.ancsbridge.ui

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Text
import com.wearos.ancsbridge.R
import com.wearos.ancsbridge.alarm.WakeAlarm
import com.wearos.ancsbridge.ancs.AncsService
import com.wearos.ancsbridge.ui.theme.AncsBridgeTheme
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

/**
 * Full-screen wake-up alarm: the time and a big Stop button (plus Snooze when
 * the iPhone alarm offers it). Closes itself when the alarm stops anywhere.
 */
class WakeAlarmActivity : ComponentActivity() {

    companion object {
        const val EXTRA_SNOOZE_AVAILABLE = "snooze_available"
    }

    private val snoozeAvailable = mutableStateOf(false)

    private val stoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this, null)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        registerReceiver(stoppedReceiver, IntentFilter(WakeAlarm.ACTION_STOPPED), RECEIVER_NOT_EXPORTED)
        snoozeAvailable.value = intent.getBooleanExtra(EXTRA_SNOOZE_AVAILABLE, false)
        if (!WakeAlarm.isRinging) {
            finish() // opened late, after the alarm already stopped
            return
        }

        setContent {
            AncsBridgeTheme {
                val canSnooze by snoozeAvailable
                WakeAlarmScreen(
                    canSnooze = canSnooze,
                    onStop = { stopAlarm(snooze = false) },
                    onSnooze = { stopAlarm(snooze = true) }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SNOOZE_AVAILABLE, false)) snoozeAvailable.value = true
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(stoppedReceiver)
        } catch (_: IllegalArgumentException) { }
    }

    private fun stopAlarm(snooze: Boolean) {
        startService(AncsService.stopWakeAlarmIntent(this, snooze))
        finish()
    }
}

@Composable
fun WakeAlarmScreen(canSnooze: Boolean, onStop: () -> Unit, onSnooze: () -> Unit) {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = Date()
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = DateFormat.getTimeInstance(DateFormat.SHORT).format(now),
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(R.string.wake_alarm_title),
            fontSize = 13.sp,
            color = Color.Gray
        )
        Spacer(modifier = Modifier.height(10.dp))
        Button(
            onClick = onStop,
            modifier = Modifier.size(72.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9F0A))
        ) {
            Text(
                text = stringResource(R.string.wake_alarm_stop),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
        }
        if (canSnooze) {
            Spacer(modifier = Modifier.height(6.dp))
            FilledTonalButton(onClick = onSnooze) {
                Text(text = stringResource(R.string.wake_alarm_snooze), fontSize = 13.sp)
            }
        }
    }
}
