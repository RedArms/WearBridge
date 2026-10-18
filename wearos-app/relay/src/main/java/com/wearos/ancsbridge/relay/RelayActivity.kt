package com.wearos.ancsbridge.relay

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Setup screen for the relay: grant Samsung Health access, pair the iPhone with
 * the internet relay (QR code), see the last sync, sync on demand. Syncing runs
 * every 15 minutes in the background (RelayWorker).
 */
class RelayActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var qr: ImageView
    private lateinit var server: EditText
    private lateinit var reader: SamsungHealthReader
    private lateinit var mailbox: RelayMailbox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reader = SamsungHealthReader(this)
        mailbox = RelayMailbox(this)

        val density = resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val intro = TextView(this).apply {
            text = "Sends sleep (stages and score), blood oxygen, blood pressure and heart rate from " +
                "Samsung Health to Apple Health on your iPhone.\n\n" +
                "Needs: Samsung Health developer mode, and Apple Health on in the iPhone app."
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

        val internetTitle = TextView(this).apply {
            text = "\nInternet relay to the iPhone"
            textSize = 18f
        }
        val internetIntro = TextView(this).apply {
            text = "Keep this phone at home on Wi-Fi: data reaches the iPhone wherever it is, " +
                "end-to-end encrypted with a key only this phone and your iPhone have. " +
                "Scan this code once with the iPhone camera (WearBridge opens and pairs)."
        }
        qr = ImageView(this).apply {
            adjustViewBounds = true
            setPadding(0, padding / 2, 0, padding / 2)
        }
        val copyLink = Button(this).apply {
            text = "Copy pairing link"
            setOnClickListener {
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("WearBridge relay", mailbox.link))
                Toast.makeText(this@RelayActivity, "Link copied; paste it in the iPhone app's Settings", Toast.LENGTH_LONG).show()
            }
        }
        server = EditText(this).apply {
            hint = "Mailbox server (ntfy), e.g. https://ntfy.sh"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(mailbox.pairing.server)
        }
        val useServer = Button(this).apply {
            text = "Use this server (new pairing)"
            setOnClickListener {
                val url = server.text.toString().trim().trimEnd('/')
                if (!url.startsWith("https://")) {
                    Toast.makeText(this@RelayActivity, "Use an https:// address", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                mailbox.reset(url)
                showQr()
                Toast.makeText(this@RelayActivity, "New pairing: scan the code again on the iPhone", Toast.LENGTH_LONG).show()
            }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(intro)
            addView(grant)
            addView(syncNow)
            addView(status)
            addView(internetTitle)
            addView(internetIntro)
            addView(qr)
            addView(copyLink)
            addView(server)
            addView(useServer)
        }
        setContentView(ScrollView(this).apply { addView(content) })

        showQr()
        RelayWorker.schedule(this)
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(RelayWorker.ONE_TIME_WORK_NAME)
            .observe(this) { refreshStatus() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun showQr() {
        val size = 720
        val matrix = QRCodeWriter().encode(
            mailbox.link, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2)
        )
        val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE }
        qr.setImageBitmap(Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888))
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
