package com.mango.ytstream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.audio.InternalAudioSource
import com.pedro.encoder.input.sources.video.ScreenSource
import com.pedro.library.rtmp.RtmpStream

class StreamService : Service(), ConnectChecker {

    companion object {
        var mainActivity: MainActivity? = null
        const val CHANNEL_ID = "ytstream_channel"
        const val NOTIF_ID = 1
    }

    private var rtmpStream: RtmpStream? = null
    private var mediaProjection: MediaProjection? = null

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

        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = manager.getMediaProjection(resultCode, data)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mediaProjection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopStreaming() }
            }, Handler(Looper.getMainLooper()))
        }

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(metrics)
        val screenW = (metrics.widthPixels / 2) * 2
        val screenH = (metrics.heightPixels / 2) * 2

        Thread {
            try {
                // Step 1: Create RtmpStream first
                rtmpStream = RtmpStream(applicationContext, this@StreamService)

                // Step 2: prepare BEFORE changing sources
                val videoPrepared = rtmpStream!!.prepareVideo(screenW, screenH, 2_500_000)
                val audioPrepared = rtmpStream!!.prepareAudio(44100, true, 128_000)

                if (!videoPrepared || !audioPrepared) {
                    mainActivity?.notifyFlutter("onStreamError", "Encoder init failed")
                    stopSelf()
                    return@Thread
                }

                // Step 3: change sources AFTER prepare
                val screenSource = ScreenSource(applicationContext, mediaProjection!!)
                val audioSource = InternalAudioSource(mediaProjection!!)
                rtmpStream!!.changeVideoSource(screenSource)
                rtmpStream!!.changeAudioSource(audioSource)

                // Step 4: start stream
                rtmpStream!!.startStream("$rtmpUrl/$streamKey")

            } catch (e: Exception) {
                mainActivity?.notifyFlutter("onStreamError", e.message ?: "Unknown error")
                stopSelf()
            }
        }.start()

        return START_NOT_STICKY
    }

    override fun onConnectionStarted(url: String) {}
    override fun onConnectionSuccess() {
        mainActivity?.notifyFlutter("onStreamStarted")
    }
    override fun onConnectionFailed(reason: String) {
        mainActivity?.notifyFlutter("onStreamError", reason)
        stopSelf()
    }
    override fun onNewBitrate(bitrate: Long) {
        mainActivity?.notifyFlutter("onBitrateUpdate", "${bitrate / 1000}")
    }
    override fun onDisconnect() {
        mainActivity?.notifyFlutter("onStreamStopped")
    }
    override fun onAuthError() {
        mainActivity?.notifyFlutter("onStreamError", "Auth error - check stream key")
    }
    override fun onAuthSuccess() {}

    private fun stopStreaming() {
        try { rtmpStream?.stopStream() } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
        stopForeground(true)
        stopSelf()
        mainActivity?.notifyFlutter("onStreamStopped")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "YT Stream", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Streaming to YouTube" }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🔴 YT Stream - LIVE")
            .setContentText("Streaming screen + internal audio")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }
}
