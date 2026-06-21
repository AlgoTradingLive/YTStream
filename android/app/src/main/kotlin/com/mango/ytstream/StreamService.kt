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
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.audio.InternalAudioSource
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.video.NoVideoSource
import com.pedro.encoder.input.sources.video.ScreenSource
import com.pedro.library.generic.GenericStream

class StreamService : Service(), ConnectChecker {

    companion object {
        var mainActivity: MainActivity? = null
        const val CHANNEL_ID = "ytstream_channel"
        const val NOTIF_ID = 1
        const val TAG = "YTStream"
    }

    private lateinit var genericStream: GenericStream
    private var mediaProjection: MediaProjection? = null
    private val mediaProjectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    private var prepared = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        genericStream = GenericStream(applicationContext, this, NoVideoSource(), MicrophoneSource()).apply {
            getGlInterface().setForceRender(true, 15)
        }

        // Try multiple configs — portrait device needs rotation=90
        val configs = listOf(
            listOf(1280, 720, 0, 32000),
            listOf(1280, 720, 90, 32000),
            listOf(720, 1280, 0, 32000),
            listOf(854, 480, 0, 32000),
            listOf(640, 480, 0, 32000),
        )

        for ((w, h, rot, sr) in configs) {
            prepared = try {
                genericStream.prepareVideo(w, h, 2_000_000, rotation = rot) &&
                genericStream.prepareAudio(sr, true, 128_000)
            } catch (e: Exception) {
                Log.e(TAG, "prepare ${w}x${h} rot=$rot failed: ${e.message}")
                false
            }
            if (prepared) {
                Log.i(TAG, "Prepared OK: ${w}x${h} rot=$rot sr=$sr")
                break
            }
        }

        if (!prepared) Log.e(TAG, "All prepare configs failed")
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

        if (!prepared) {
            mainActivity?.notifyFlutter("onStreamError", "Encoder prepare failed — device not supported")
            return START_NOT_STICKY
        }

        mediaProjection?.stop()
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mediaProjection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopStreaming() }
            }, Handler(Looper.getMainLooper()))
        }

        try {
            val screenSource = ScreenSource(applicationContext, mediaProjection!!)
            genericStream.changeVideoSource(screenSource)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                genericStream.changeAudioSource(InternalAudioSource(mediaProjection!!))
            }

            genericStream.startStream("$rtmpUrl/$streamKey")

        } catch (e: Exception) {
            mainActivity?.notifyFlutter("onStreamError", "${e.javaClass.simpleName}: ${e.message}")
            stopSelf()
        }

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
        try { genericStream.release() } catch (_: Exception) {}
        mediaProjection?.stop()
        mediaProjection = null
    }

    private fun stopStreaming() {
        if (::genericStream.isInitialized && genericStream.isStreaming) genericStream.stopStream()
        mediaProjection?.stop()
        mediaProjection = null
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
