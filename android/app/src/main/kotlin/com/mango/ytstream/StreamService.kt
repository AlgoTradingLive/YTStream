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
import androidx.core.app.NotificationCompat
import com.pedro.encoder.input.gl.render.filters.NoFilterRender
import com.pedro.library.rtmp.RtmpStream
import com.pedro.library.util.sources.audio.InternalAudioSource
import com.pedro.library.util.sources.video.ScreenSource

class StreamService : Service() {

    companion object {
        var mainActivity: MainActivity? = null
        const val CHANNEL_ID = "ytstream_channel"
        const val NOTIF_ID = 1
    }

    private var rtmpStream: RtmpStream? = null

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
        val mediaProjection = manager.getMediaProjection(resultCode, data)

        // Register callback for Android 14+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mediaProjection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopStreaming() }
            }, Handler(Looper.getMainLooper()))
        }

        Thread {
            try {
                // RootEncoder handles screen + internal audio + RTMP
                val screenSource = ScreenSource(applicationContext, mediaProjection)
                val audioSource = InternalAudioSource(mediaProjection)

                rtmpStream = RtmpStream(applicationContext, audioSource, screenSource)

                rtmpStream!!.prepareVideo(
                    width = 1280,
                    height = 720,
                    fps = 30,
                    bitrate = 2_500_000
                )
                rtmpStream!!.prepareAudio(
                    sampleRate = 44100,
                    isStereo = true,
                    bitrate = 128_000
                )

                rtmpStream!!.startStream("$rtmpUrl/$streamKey")
                mainActivity?.notifyFlutter("onStreamStarted")

            } catch (e: Exception) {
                mainActivity?.notifyFlutter("onStreamError", e.message ?: "Stream error")
                stopSelf()
            }
        }.start()

        return START_NOT_STICKY
    }

    private fun stopStreaming() {
        try {
            rtmpStream?.stopStream()
        } catch (_: Exception) {}
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
            .setContentText("Streaming screen + internal audio to YouTube")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }
}
