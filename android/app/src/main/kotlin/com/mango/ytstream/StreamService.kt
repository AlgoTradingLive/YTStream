package com.mango.ytstream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpDisplay

class StreamService : Service(), ConnectChecker {

    companion object {
        var mainActivity: MainActivity? = null
        const val CHANNEL_ID = "ytstream_channel"
        const val NOTIF_ID = 1
    }

    private var rtmpDisplay: RtmpDisplay? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun notify(msg: String) {
        mainHandler.post {
            mainActivity?.notifyFlutter("onStreamError", msg)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopStreaming()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY
        val rtmpUrl = intent.getStringExtra("rtmpUrl") ?: return START_NOT_STICKY
        val streamKey = intent.getStringExtra("streamKey") ?: return START_NOT_STICKY

        startForeground(NOTIF_ID, buildNotification())

        notify("Step1: Init...")

        mainHandler.post {
            try {
                notify("Step2: Creating display...")
                rtmpDisplay = RtmpDisplay(applicationContext, true, this@StreamService)
                rtmpDisplay!!.glInterface.setForceRender(true)
                rtmpDisplay!!.setIntentResult(resultCode, data)

                notify("Step3: PrepareVideo...")
                val videoOk = rtmpDisplay!!.prepareVideo(1280, 720, 2_000_000)

                notify("Step4: PrepareAudio...")
                val audioOk = rtmpDisplay!!.prepareInternalAudio(128_000, 44100, true)

                notify("Step5: V:$videoOk A:$audioOk")

                if (videoOk && audioOk) {
                    notify("Step6: Starting stream...")
                    rtmpDisplay!!.startStream("$rtmpUrl/$streamKey")
                } else {
                    notify("Prepare failed V:$videoOk A:$audioOk")
                    stopSelf()
                }
            } catch (e: Exception) {
                notify("EX: ${e.javaClass.simpleName}: ${e.message}")
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onConnectionStarted(url: String) { notify("Connected!") }
    override fun onConnectionSuccess() { mainHandler.post { mainActivity?.notifyFlutter("onStreamStarted") } }
    override fun onConnectionFailed(reason: String) { notify("FAILED: $reason"); stopSelf() }
    override fun onNewBitrate(bitrate: Long) { mainHandler.post { mainActivity?.notifyFlutter("onBitrateUpdate", "${bitrate/1000}") } }
    override fun onDisconnect() { notify("Disconnected"); stopSelf() }
    override fun onAuthError() { notify("Auth error") }
    override fun onAuthSuccess() {}

    override fun onDestroy() {
        super.onDestroy()
        try { if (rtmpDisplay?.isStreaming == true) rtmpDisplay?.stopStream() } catch (_: Exception) {}
    }

    private fun stopStreaming() {
        try { if (rtmpDisplay?.isStreaming == true) rtmpDisplay?.stopStream() } catch (_: Exception) {}
        stopForeground(true)
        stopSelf()
        mainHandler.post { mainActivity?.notifyFlutter("onStreamStopped") }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "YT Stream", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🔴 YT Stream")
            .setContentText("Streaming to YouTube")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true).build()
    }
}
