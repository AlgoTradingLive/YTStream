package com.mango.ytstream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
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

        Thread {
            try {
                // RtmpDisplay is specifically designed for screen streaming
                rtmpDisplay = RtmpDisplay(applicationContext, true, this@StreamService)
                rtmpDisplay!!.glInterface.setForceRender(true)

                // Set MediaProjection result
                rtmpDisplay!!.setIntentResult(resultCode, data)

                // prepareVideo and prepareAudio with no params uses defaults
                val videoOk = rtmpDisplay!!.prepareVideo(1280, 720, 30, 2_000_000, 0)
                val audioOk = rtmpDisplay!!.prepareAudio(128_000, 44100, true)

                if (videoOk && audioOk) {
                    rtmpDisplay!!.startStream("$rtmpUrl/$streamKey")
                } else {
                    mainActivity?.notifyFlutter("onStreamError", "Prepare failed V:$videoOk A:$audioOk")
                    stopSelf()
                }
            } catch (e: Exception) {
                mainActivity?.notifyFlutter("onStreamError", "${e.javaClass.simpleName}: ${e.message}")
                stopSelf()
            }
        }.start()

        return START_NOT_STICKY
    }

    override fun onConnectionStarted(url: String) {}
    override fun onConnectionSuccess() { mainActivity?.notifyFlutter("onStreamStarted") }
    override fun onConnectionFailed(reason: String) {
        mainActivity?.notifyFlutter("onStreamError", reason); stopSelf()
    }
    override fun onNewBitrate(bitrate: Long) {
        mainActivity?.notifyFlutter("onBitrateUpdate", "${bitrate / 1000}")
    }
    override fun onDisconnect() { mainActivity?.notifyFlutter("onStreamStopped") }
    override fun onAuthError() { mainActivity?.notifyFlutter("onStreamError", "Auth error") }
    override fun onAuthSuccess() {}

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
    }

    private fun stopStreaming() {
        try { if (rtmpDisplay?.isStreaming == true) rtmpDisplay?.stopStream() } catch (_: Exception) {}
        stopForeground(true)
        stopSelf()
        mainActivity?.notifyFlutter("onStreamStopped")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "YT Stream", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🔴 YT Stream - LIVE")
            .setContentText("Streaming screen + internal audio to YouTube")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true).build()
    }
}
