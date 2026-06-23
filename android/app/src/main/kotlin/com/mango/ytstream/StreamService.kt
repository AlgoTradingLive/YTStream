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
    private var savedResultCode = -1
    private var savedData: Intent? = null

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
                genericStream?.changeAudioSource(SilenceAudioSource())
                mainHandler.post { mainActivity?.notifyFlutter("onStreamError", "⏸ Paused") }
                return START_NOT_STICKY
            }
            "RESUME" -> {
                rtmpDisplay?.enableAudio()
                mixAudioSource?.let { genericStream?.changeAudioSource(it) }
                mainHandler.post { mainActivity?.notifyFlutter("onStreamStarted") }
                return START_NOT_STICKY
            }
            "MUTE" -> {
                rtmpDisplay?.disableAudio()
                genericStream?.changeAudioSource(SilenceAudioSource())
                mainHandler.post { mainActivity?.notifyFlutter("onStreamError", "🔇 Muted") }
                return START_NOT_STICKY
            }
            "UNMUTE" -> {
                rtmpDisplay?.enableAudio()
                mixAudioSource?.let { genericStream?.changeAudioSource(it) }
                mainHandler.post { mainActivity?.notifyFlutter("onStreamStarted") }
                return START_NOT_STICKY
            }
        }

        val resultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY
        val rtmpUrl = intent.getStringExtra("rtmpUrl") ?: return START_NOT_STICKY
        val streamKey = intent.getStringExtra("streamKey") ?: return START_NOT_STICKY
        val audioMode = intent.getStringExtra("audioMode") ?: "internal"
        val orientation = intent.getStringExtra("orientation") ?: "landscape"

        savedResultCode = resultCode
        savedData = data

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fgsType = if (audioMode == "mic_internal")
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            else
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            startForeground(NOTIF_ID, buildNotification(), fgsType)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        acquireWakeLock()

        val isPortrait = orientation == "portrait"
        // Get actual screen dimensions
        val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        val dm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getMetrics(dm)
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val isScreenPortrait = screenH > screenW

        // Portrait streaming: use 720x1280 with correct rotation
        val vW: Int
        val vH: Int
        val rotation: Int
        if (isPortrait) {
            vW = 720
            vH = 1280
            rotation = if (isScreenPortrait) 0 else 90
        } else {
            vW = 1280
            vH = 720
            rotation = if (isScreenPortrait) 90 else 0
        }
        val fullUrl = "$rtmpUrl/$streamKey"

        mainHandler.post {
            try {
                if (audioMode == "mic_internal" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startMixedAudio(fullUrl, vW, vH, rotation, resultCode, data)
                } else {
                    startInternalOnly(fullUrl, vW, vH, rotation, resultCode, data)
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
    private fun startMixedAudio(url: String, w: Int, h: Int, rotation: Int, rc: Int, d: Intent) {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp: MediaProjection = manager.getMediaProjection(rc, d)!!

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopStreaming() }
            }, mainHandler)
        }

        // MixAudioSource: mic + internal audio एकत्र
        val mix = MixAudioSource(mp)
        mixAudioSource = mix
        val screen = ScreenSource(applicationContext, mp)

        // GenericStream मध्ये MixAudioSource pass करतो — हे Mic+Internal दोन्ही capture करतो
        genericStream = GenericStream(applicationContext, this, screen, mix).apply {
            getGlInterface().setForceRender(true)
        }

        val vOk = genericStream!!.prepareVideo(w, h, 2_000_000, 24, 2, rotation)
        // echoCanceler + noiseSuppressor = mic echo कमी होतो
        val aOk = genericStream!!.prepareAudio(44100, true, 128_000, echoCanceler = true, noiseSuppressor = true)

        if (vOk && aOk) {
            // Mic volume वाढवतो — internal audio normally जास्त असतो
            mix.microphoneVolume = 2f
            genericStream!!.startStream(url)
        } else {
            notify("Mic+Internal failed V:$vOk A:$aOk")
            try { genericStream?.release() } catch (_: Exception) {}
            genericStream = null
            mixAudioSource = null
            mp.stop()
            startInternalOnly(url, w, h, rotation, savedResultCode, savedData!!)
        }
    }

    private fun startInternalOnly(url: String, w: Int, h: Int, rotation: Int, rc: Int, d: Intent) {
        rtmpDisplay = RtmpDisplay(applicationContext, true, this@StreamService)
        rtmpDisplay!!.glInterface.setForceRender(true)
        rtmpDisplay!!.setIntentResult(rc, d)

        val vOk = rtmpDisplay!!.prepareVideo(w, h, 2_000_000, 24, 2, rotation)
        var aOk = false
        for ((br, sr, st) in listOf(
            Triple(128_000, 44100, true),
            Triple(128_000, 44100, false),
            Triple(64_000, 44100, true),
            Triple(64_000, 32000, true),
        )) {
            aOk = try { rtmpDisplay!!.prepareInternalAudio(br, sr, st, false, false) }
                  catch (_: Exception) { false }
            if (aOk) break
        }
        if (!aOk) aOk = rtmpDisplay!!.prepareAudio(64_000, 44100, true)

        if (vOk && aOk) {
            rtmpDisplay!!.startStream(url)
        } else {
            notify("Prepare failed V:$vOk A:$aOk")
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


