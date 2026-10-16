package com.wearos.ancsbridge.alarm

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import com.wearos.ancsbridge.AncsApplication
import com.wearos.ancsbridge.R
import com.wearos.ancsbridge.ancs.AncsService
import com.wearos.ancsbridge.ui.WakeAlarmActivity

/**
 * The watch side of the wake-up alarm: a vibration that starts barely
 * noticeable and grows ([WakeRamp]), plus a full-screen "Stop" screen.
 *
 * Started when the iPhone says its alarm is ringing (companion wake alarm
 * characteristic, driven by a Shortcuts automation) or when an iPhone Clock
 * alarm arrives over ANCS. Stops when the iPhone says so, from the watch
 * screen, or after [MAX_RING_MS].
 */
object WakeAlarm {

    private const val TAG = "WakeAlarm"
    const val NOTIFICATION_ID = 998
    const val ACTION_STOPPED = "com.wearos.ancsbridge.WAKE_ALARM_STOPPED"
    private const val MAX_RING_MS = 10 * 60_000L

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    var isRinging = false
        private set

    /** Starts the alarm if it isn't ringing already. [snoozeAvailable]: the watch can snooze the iPhone alarm. */
    @SuppressLint("WakelockTimeout")
    fun start(context: Context, snoozeAvailable: Boolean) {
        val app = context.applicationContext
        if (isRinging) {
            if (snoozeAvailable) showScreen(app, snoozeAvailable = true) // now we know more
            return
        }
        isRinging = true
        Log.i(TAG, "Wake alarm ringing")

        wakeLock = app.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WearBridge:WakeAlarm")
            .apply { acquire(MAX_RING_MS + 60_000L) }

        vibrate(app)
        showScreen(app, snoozeAvailable)
        handler.postDelayed({ stop(app) }, MAX_RING_MS)
    }

    fun stop(context: Context) {
        if (!isRinging) return
        isRinging = false
        Log.i(TAG, "Wake alarm stopped")
        val app = context.applicationContext
        handler.removeCallbacksAndMessages(null)
        app.getSystemService(Vibrator::class.java).cancel()
        app.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        app.sendBroadcast(Intent(ACTION_STOPPED).setPackage(app.packageName))
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun vibrate(context: Context) {
        val vibrator = context.getSystemService(Vibrator::class.java)
        val ramp = WakeRamp.build()
        val effect = if (vibrator.hasAmplitudeControl()) {
            VibrationEffect.createWaveform(ramp.timings, ramp.amplitudes, ramp.repeatIndex)
        } else {
            VibrationEffect.createWaveform(ramp.timings, ramp.repeatIndex)
        }
        // Alarm usage: goes through Bedtime mode / Do Not Disturb like the watch's own alarms,
        // and notification vibrations don't cut it off.
        vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
    }

    private fun showScreen(context: Context, snoozeAvailable: Boolean) {
        val screen = Intent(context, WakeAlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_NO_USER_ACTION
            putExtra(WakeAlarmActivity.EXTRA_SNOOZE_AVAILABLE, snoozeAvailable)
        }
        val fullScreen = PendingIntent.getActivity(
            context, 0, screen, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            context, 1, AncsService.stopWakeAlarmIntent(context, snooze = false),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, AncsApplication.CHANNEL_WAKE_ALARM)
            .setSmallIcon(R.drawable.ic_schedule)
            .setContentTitle(context.getString(R.string.wake_alarm_title))
            .setContentText(context.getString(R.string.wake_alarm_text))
            .setCategory(Notification.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .addAction(R.drawable.ic_close, context.getString(R.string.wake_alarm_stop), stopIntent)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)

        // Full-screen intents alone aren't reliable on Wear OS (gotcha 5).
        try {
            context.startActivity(screen)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't open the alarm screen directly: ${e.message}")
        }
    }
}
