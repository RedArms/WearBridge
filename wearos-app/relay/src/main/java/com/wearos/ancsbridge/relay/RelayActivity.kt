package com.wearos.ancsbridge.relay

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Setup screen for the relay: grant Samsung Health access, see the last sync,
 * sync on demand. Syncing itself runs hourly in the background (RelayWorker).
 */
class RelayActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var reader: SamsungHealthReader

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reader = SamsungHealthReader(this)

        val padding = (16 * resources.displayMetrics.density).toInt()
        val intro = TextView(this).apply {
            text = "Sends sleep (stages and score), blood oxygen, blood pressure and heart rate from " +
                "Samsung Health to the WearBridge watch app, which forwards them to Apple Health on your iPhone.\n\n" +
                "Needs: Samsung Health developer mode, Health Sync on in the watch app, Apple Health on in the iPhone app."
        }
        val grant = Button(this).apply {
            text = "Grant Samsung Health access"
            setOnClickListener { requestAccess() }
        }
        val syncNow = Button(this).apply {
            text = "Sync now"
            setOnClickListener {
                RelayWorker.syncNow(this@RelayActivity)
                status.text = "Syncing…"
            }
        }
        status = TextView(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(intro)
            addView(grant)
            addView(syncNow)
            addView(status)
        }
        setContentView(ScrollView(this).apply { addView(content) })

        RelayWorker.schedule(this)
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(RelayWorker.ONE_TIME_WORK_NAME)
            .observe(this) { refreshStatus() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun requestAccess() {
        lifecycleScope.launch {
            try {
                reader.requestPermissions(this@RelayActivity)
                RelayWorker.syncNow(this@RelayActivity)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status.text = SamsungHealthReader.explain(e) ?: (e.message ?: e.toString())
                return@launch
            }
            refreshStatus()
        }
    }

    private fun refreshStatus() {
        lifecycleScope.launch {
            val access = try {
                val granted = reader.grantedPermissions().size
                "Samsung Health access: $granted of ${SamsungHealthReader.PERMISSIONS.size} data types"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SamsungHealthReader.explain(e) ?: (e.message ?: e.toString())
            }
            val last = RelayState(this@RelayActivity).lastMessage ?: "Not synced yet"
            status.text = "$access\n\n$last"
        }
    }
}
