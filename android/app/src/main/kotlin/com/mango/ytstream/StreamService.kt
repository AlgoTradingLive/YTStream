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
        mainHandler.post { mainActivity?.notifyFlutter("onStreamError", msg) }
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

        mainHandler.post {
            try {
                rtmpDisplay = RtmpDisplay(applicationContext, true, this@StreamService)
                rtmpDisplay!!.glInterface.setForceRender(true)
                rtmpDisplay!!.setIntentResult(resultCode, data)

                val videoOk = rtmpDisplay!!.prepareVideo(1280, 720, 2_000_000)

                // Try multiple audio configs for Samsung compatibility
                val audioConfigs = listOf(
                    Triple(128_000, 44100, true),
                    Triple(128_000, 44100, false),
                    Triple(64_000, 44100, true),
                    Triple(64_000, 32000, true),
                    Triple(64_000, 16000, true),
                    Triple(64_000, 16000, false),
                )

                var audioOk = false
                for ((bitrate, sampleRate, stereo) in audioConfigs) {
                    audioOk = try {
                        rtmpDisplay!!.prepareInternalAudio(bitrate, sampleRate, stereo, false, false)
                    } catch (e: Exception) { false }
                    if (audioOk) break
                }

                // Samsung fallback: use mic if internal audio fails
                if (!audioOk) {
                    notify("Internal audio failed on this device, trying mic...")
                    audioOk = rtmpDisplay!!.prepareAudio(64_000, 44100, true)
                }

                if (videoOk && audioOk) {
                    rtmpDisplay!!.startStream("$rtmpUrl/$streamKey")
                } else {
                    notify("Prepare failed V:$videoOk A:$audioOk")
                    stopSelf()
                }
            } catch (e: Exception) {
                notify("Error: ${e.javaClass.simpleName}: ${e.message}")
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onConnectionStarted(url: String) {}
    override fun onConnectionSuccess() {
        mainHandler.post { mainActivity?.notifyFlutter("onStreamStarted") }
    }
    override fun onConnectionFailed(reason: String) {
        notify("Failed: $reason"); stopSelf()
    }
    override fun onNewBitrate(bitrate: Long) {
        mainHandler.post { mainActivity?.notifyFlutter("onBitrateUpdate", "${bitrate / 1000}") }
    }
    override fun onDisconnect() {
        notify("Disconnected"); stopSelf()
    }
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
            .setContentTitle("🔴 YT Stream - LIVE")
            .setContentText("Streaming to YouTube")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true).build()
    }
}
