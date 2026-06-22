package com.mango.ytstream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.audio.MixAudioSource
import com.pedro.encoder.input.sources.audio.SilenceAudioSource
import com.pedro.encoder.input.sources.video.ScreenSource
import com.pedro.library.generic.GenericStream
import com.pedro.library.rtmp.RtmpDisplay

class StreamService : Service(), ConnectChecker {

    companion object {
        var mainActivity: MainActivity? = null
        const val CHANNEL_ID = "ytstream_channel"
        const val NOTIF_ID = 1
    }

    private var rtmpDisplay: RtmpDisplay? = null
    private var genericStream: GenericStream? = null
    private var mixAudioSource: MixAudioSource? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var mediaProjection: MediaProjection? = null
    private var currentAudioMode = "internal"
    private var isMuted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerScreenReceiver()
    }

    private fun notify(msg: String) {
        mainHandler.post { mainActivity?.notifyFlutter("onStreamError", msg) }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YTStream::Lock").apply {
            acquire(8 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    private fun registerScreenReceiver() {
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                rtmpDisplay?.glInterface?.setForceRender(true)
                genericStream?.getGlInterface()?.setForceRender(true)
            }
        }
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP" -> { stopStreaming(); return START_NOT_STICKY }
            "PAUSE" -> {
                rtmpDisplay?.disableAudio()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    genericStream?.changeAudioSource(SilenceAudioSource())
                }
                mainHandler.post { mainActivity?.notifyFlutter("onStreamError", "⏸ Paused") }
                return START_NOT_STICKY
            }
            "RESUME" -> {
                rtmpDisplay?.enableAudio()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mixAudioSource != null) {
                    genericStream?.changeAudioSource(mixAudioSource!!)
                }
                isMuted = false
                mainHandler.post { mainActivity?.notifyFlutter("onStreamStarted") }
                return START_NOT_STICKY
            }
            "MUTE" -> {
                isMuted = true
                rtmpDisplay?.disableAudio()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    genericStream?.changeAudioSource(SilenceAudioSource())
                }
                mainHandler.post { mainActivity?.notifyFlutter("onStreamError", "🔇 Muted") }
                return START_NOT_STICKY
            }
            "UNMUTE" -> {
                isMuted = false
                rtmpDisplay?.enableAudio()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mixAudioSource != null) {
                    genericStream?.changeAudioSource(mixAudioSource!!)
                }
                mainHandler.post { mainActivity?.notifyFlutter("onStreamStarted") }
                return START_NOT_STICKY
            }
        }

        val resultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY
        val rtmpUrl = intent.getStringExtra("rtmpUrl") ?: return START_NOT_STICKY
        val streamKey = intent.getStringExtra("streamKey") ?: return START_NOT_STICKY
        currentAudioMode = intent.getStringExtra("audioMode") ?: "internal"

        startForeground(NOTIF_ID, buildNotification())
        acquireWakeLock()

        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = manager.getMediaProjection(resultCode, data)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mediaProjection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopStreaming() }
            }, mainHandler)
        }

        mainHandler.post {
            try {
                if (currentAudioMode == "mic_internal" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startWithMixedAudio("$rtmpUrl/$streamKey")
                } else {
                    startWithInternalOnly(resultCode, data, "$rtmpUrl/$streamKey")
                }
            } catch (e: Exception) {
                notify("Error: ${e.message}")
                releaseWakeLock()
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startWithMixedAudio(fullUrl: String) {
        mixAudioSource = MixAudioSource(mediaProjection!!)
        val screenSource = ScreenSource(applicationContext, mediaProjection!!)

        genericStream = GenericStream(applicationContext, this, screenSource, mixAudioSource!!).apply {
            getGlInterface().setForceRender(true)
        }

        val videoOk = genericStream!!.prepareVideo(1280, 720, 2_000_000)
        var audioOk = genericStream!!.prepareAudio(44100, true, 128_000, echoCanceler = false, noiseSuppressor = false)
                if (!audioOk) audioOk = genericStream!!.prepareAudio(44100, false, 128_000)
                if (!audioOk) audioOk = genericStream!!.prepareAudio(32000, true, 64_000)

        if (videoOk && audioOk) {
            genericStream!!.startStream(fullUrl)
        } else {
            notify("Prepare failed V:$videoOk A:$audioOk")
            releaseWakeLock()
            stopSelf()
        }
    }

    private fun startWithInternalOnly(resultCode: Int, data: Intent, fullUrl: String) {
        rtmpDisplay = RtmpDisplay(applicationContext, true, this@StreamService)
        rtmpDisplay!!.glInterface.setForceRender(true)
        rtmpDisplay!!.setIntentResult(resultCode, data)

        val videoOk = rtmpDisplay!!.prepareVideo(1280, 720, 2_000_000)
        var audioOk = false
        for ((bitrate, sampleRate, stereo) in listOf(
            Triple(128_000, 44100, true),
            Triple(128_000, 44100, false),
            Triple(64_000, 44100, true),
            Triple(64_000, 32000, true),
        )) {
            audioOk = try { rtmpDisplay!!.prepareInternalAudio(bitrate, sampleRate, stereo, false, false) }
                      catch (e: Exception) { false }
            if (audioOk) break
        }
        if (!audioOk) audioOk = rtmpDisplay!!.prepareAudio(64_000, 44100, true)

        if (videoOk && audioOk) {
            rtmpDisplay!!.startStream(fullUrl)
        } else {
            notify("Prepare failed V:$videoOk A:$audioOk")
            releaseWakeLock()
            stopSelf()
        }
    }

    override fun onConnectionStarted(url: String) {}
    override fun onConnectionSuccess() {
        mainHandler.post { mainActivity?.notifyFlutter("onStreamStarted") }
    }
    override fun onConnectionFailed(reason: String) {
        notify("Failed: $reason"); releaseWakeLock(); stopSelf()
    }
    override fun onNewBitrate(bitrate: Long) {
        mainHandler.post { mainActivity?.notifyFlutter("onBitrateUpdate", "${bitrate / 1000}") }
    }
    override fun onDisconnect() {
        notify("Disconnected"); releaseWakeLock(); stopSelf()
    }
    override fun onAuthError() { notify("Auth error") }
    override fun onAuthSuccess() {}

    override fun onDestroy() {
        super.onDestroy()
        try { screenReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        try { if (rtmpDisplay?.isStreaming == true) rtmpDisplay?.stopStream() } catch (_: Exception) {}
        try { if (genericStream?.isStreaming == true) genericStream?.stopStream() } catch (_: Exception) {}
        releaseWakeLock()
    }

    private fun stopStreaming() {
        try { if (rtmpDisplay?.isStreaming == true) rtmpDisplay?.stopStream() } catch (_: Exception) {}
        try { if (genericStream?.isStreaming == true) genericStream?.stopStream() } catch (_: Exception) {}
        releaseWakeLock()
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
