package com.mango.ytstream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.Color
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
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.audio.MixAudioSource
import com.pedro.encoder.input.sources.audio.SilenceAudioSource
import com.pedro.encoder.input.sources.video.ScreenSource
import com.pedro.library.generic.GenericStream
import com.pedro.library.base.StreamBase
import com.pedro.library.rtmp.RtmpDisplay
import com.pedro.encoder.input.gl.render.filters.`object`.TextObjectFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender

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
    private var savedFullUrl = ""
    private var savedOrientation = "landscape"
    private var currentVoiceMode = "normal"

    // Overlay filters
    private var textFilter: TextObjectFilterRender? = null
    private var imageFilter: ImageObjectFilterRender? = null

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

    private fun getMediaProjection(resultCode: Int, data: Intent): MediaProjection {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return manager.getMediaProjection(resultCode, data)!!
    }

    private fun applyVoiceEffect(mode: String) {
        // Pedro 2.7.3 मध्ये pitch effect नाही
    }

    private fun applyOverlay(
    overlayText: String,
    overlayImagePath: String,
    textX: Float, textY: Float,
    imageX: Float, imageY: Float
) {
    try {
        val glInterface = genericStream?.getGlInterface() ?: rtmpDisplay?.glInterface ?: return

        textFilter?.let { try { glInterface.removeFilter(it) } catch (_: Exception) {} }
        imageFilter?.let { try { glInterface.removeFilter(it) } catch (_: Exception) {} }
        textFilter = null
        imageFilter = null

        if (overlayText.isNotEmpty()) {
            val tf = TextObjectFilterRender()
            tf.setScale(40f, 8f)
            tf.setPosition(textX * 100f, textY * 100f)
            tf.setText(overlayText, 72f, Color.WHITE)
            glInterface.addFilter(tf)
            textFilter = tf
        }

        if (overlayImagePath.isNotEmpty()) {
            // ✅ High quality bitmap
            val opts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(overlayImagePath, opts)
            val opts2 = BitmapFactory.Options().apply {
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                inSampleSize = 1
                inScaled = false
            }
            val bitmap = BitmapFactory.decodeFile(overlayImagePath, opts2)
            if (bitmap != null) {
                val sf = ImageObjectFilterRender()
                val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
                val scaleH = 20f
                val scaleW = scaleH * ratio
                sf.setScale(scaleW, scaleH)
                sf.setPosition(imageX * 100f, imageY * 100f)
                sf.setImage(bitmap)
                glInterface.addFilter(sf)
                imageFilter = sf
            }
        }
    } catch (e: Exception) {
        notify("Overlay error: ${e.message}")
    }
    
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
            "MIC_MUTE" -> {
                mixAudioSource?.microphoneVolume = 0f
                mainHandler.post { mainActivity?.notifyFlutter("onStreamError", "🎤 Mic Muted") }
                return START_NOT_STICKY
            }
            "MIC_UNMUTE" -> {
                mixAudioSource?.microphoneVolume = 2f
                mainHandler.post { mainActivity?.notifyFlutter("onStreamStarted") }
                return START_NOT_STICKY
            }
            "SET_VOICE" -> {
                val mode = intent.getStringExtra("voiceMode") ?: "normal"
                currentVoiceMode = mode
                applyVoiceEffect(mode)
                return START_NOT_STICKY
            }
            "UPDATE_OVERLAY" -> {
    val isStreaming = rtmpDisplay?.isStreaming == true || genericStream?.isStreaming == true
    if (!isStreaming) return START_NOT_STICKY  // streaming नसेल तर skip

    val text = intent.getStringExtra("overlayText") ?: ""
    val imagePath = intent.getStringExtra("overlayImagePath") ?: ""
    val tx = intent.getFloatExtra("textX", 0.05f)
    val ty = intent.getFloatExtra("textY", 0.05f)
    val ix = intent.getFloatExtra("imageX", 0.7f)
    val iy = intent.getFloatExtra("imageY", 0.05f)
    // ✅ Retry 3 वेळा — glInterface ready होईपर्यंत
    var attempts = 0
    fun tryApply() {
        val gl = genericStream?.getGlInterface() ?: rtmpDisplay?.glInterface
        if (gl != null) {
            mainHandler.post { applyOverlay(text, imagePath, tx, ty, ix, iy) }
        } else if (attempts < 3) {
            attempts++
            mainHandler.postDelayed({ tryApply() }, 1000)
        }
    }
    mainHandler.post { tryApply() }
    return START_NOT_STICKY
}
        }

        val resultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY
        val rtmpUrl = intent.getStringExtra("rtmpUrl") ?: return START_NOT_STICKY
        val streamKey = intent.getStringExtra("streamKey") ?: return START_NOT_STICKY
        val audioMode = intent.getStringExtra("audioMode") ?: "internal"
        val orientation = intent.getStringExtra("orientation") ?: "landscape"
        currentVoiceMode = intent.getStringExtra("voiceMode") ?: "normal"

        val overlayText = intent.getStringExtra("overlayText") ?: ""
        val overlayImagePath = intent.getStringExtra("overlayImagePath") ?: ""
        val textX = intent.getFloatExtra("textX", 0.05f)
        val textY = intent.getFloatExtra("textY", 0.05f)
        val imageX = intent.getFloatExtra("imageX", 0.7f)
        val imageY = intent.getFloatExtra("imageY", 0.05f)

        savedResultCode = resultCode
        savedData = data
        savedOrientation = orientation
        savedFullUrl = "$rtmpUrl/$streamKey"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fgsType = if (audioMode == "mic_internal") {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            startForeground(NOTIF_ID, buildNotification(), fgsType)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        acquireWakeLock()

        val isPortrait = orientation == "portrait"
        val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        val dm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getMetrics(dm)
        val isScreenPortrait = dm.heightPixels > dm.widthPixels
        val vW = if (isPortrait) 720 else 1280
        val vH = if (isPortrait) 1280 else 720
        val rotation = if (isPortrait) { if (isScreenPortrait) 0 else 90 } else { if (isScreenPortrait) 90 else 0 }

        mainHandler.post {
            try {
                if (audioMode == "mic_internal" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startMixedAudio(savedFullUrl, vW, vH, rotation, resultCode, data, overlayText, overlayImagePath, textX, textY, imageX, imageY)
                } else {
                    startInternalOnly(savedFullUrl, vW, vH, rotation, resultCode, data, overlayText, overlayImagePath, textX, textY, imageX, imageY)
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
    private fun startMixedAudio(
        url: String, w: Int, h: Int, rotation: Int, rc: Int, d: Intent,
        overlayText: String, overlayImagePath: String,
        textX: Float, textY: Float, imageX: Float, imageY: Float
    ) {
        val mp: MediaProjection = getMediaProjection(rc, d)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopStreaming() }
            }, mainHandler)
        }

        val screen = ScreenSource(applicationContext, mp)

        genericStream = GenericStream(applicationContext, this, screen,
            MicrophoneSource(audioSource = android.media.MediaRecorder.AudioSource.MIC)).apply {
            getGlInterface().setForceRender(true)
        }

        val vOk = (genericStream as StreamBase).prepareVideo(w, h, 2_000_000, 30, 1, rotation)
        val aOk = genericStream!!.prepareAudio(
            sampleRate = 44100,
            isStereo = true,
            bitrate = 128_000,
            echoCanceler = true,
            noiseSuppressor = true
        )

        if (vOk && aOk) {
            val mix = MixAudioSource(mp)
            mixAudioSource = mix
            genericStream!!.changeAudioSource(mix)
            applyVoiceEffect(currentVoiceMode)
            genericStream!!.startStream(url)
mainHandler.postDelayed({
    applyOverlay(overlayText, overlayImagePath, textX, textY, imageX, imageY)
}, 500)
        } else {
            notify("Mic+Internal V:$vOk A:$aOk — switching to internal only")
            try { genericStream?.release() } catch (_: Exception) {}
            genericStream = null
            mixAudioSource = null
            mp.stop()
            startInternalOnly(url, w, h, rotation, savedResultCode, savedData!!, overlayText, overlayImagePath, textX, textY, imageX, imageY)
        }
    }

    private fun startInternalOnly(
        url: String, w: Int, h: Int, rotation: Int, rc: Int, d: Intent,
        overlayText: String, overlayImagePath: String,
        textX: Float, textY: Float, imageX: Float, imageY: Float
    ) {
        rtmpDisplay = RtmpDisplay(applicationContext, true, this@StreamService)
        rtmpDisplay!!.glInterface.setForceRender(true)
        rtmpDisplay!!.setVideoRotation(rotation)
        rtmpDisplay!!.setIntentResult(rc, d)

   


val vOk = rtmpDisplay!!.prepareVideo(
    width = w,
    height = h,
    fps = 30,
    bitrate = 2_000_000,
    iFrameInterval = 2  
)
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
mainHandler.postDelayed({
    applyOverlay(overlayText, overlayImagePath, textX, textY, imageX, imageY)
}, 500)
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

