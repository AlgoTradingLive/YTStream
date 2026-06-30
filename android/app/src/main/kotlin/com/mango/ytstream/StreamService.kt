package com.mango.ytstream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.audio.InternalAudioSource
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.audio.MixAudioSource
import com.pedro.encoder.input.sources.audio.SilenceAudioSource
import com.pedro.encoder.input.sources.video.ScreenSource
import com.pedro.library.generic.GenericStream
import com.pedro.library.rtmp.RtmpDisplay
import com.pedro.encoder.input.gl.render.filters.`object`.TextObjectFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender

class StreamService : Service(), ConnectChecker {

    companion object {
        var mainActivity: MainActivity? = null
        const val CHANNEL_ID = "ytstream_channel"
        const val NOTIF_ID = 1
        private const val TAG = "StreamService"
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
    private var cameraEnabled = false
    private var cameraFacing = "back"
    private var cameraMode = "pip"
    private var cameraOverlay: CameraOverlay? = null
    private var cameraFilter: ImageObjectFilterRender? = null
    private var textFilter: TextObjectFilterRender? = null
    private var imageFilter: ImageObjectFilterRender? = null
    private var lastOverlayText = ""
    private var lastOverlayImagePath = ""
    private var lastTextX = 0.05f
    private var lastTextY = 0.05f
    private var lastImageX = 0.7f
    private var lastImageY = 0.05f
    private var lastTextBold = false
    private var lastTextSize = "medium"
    private var lastTextColor = "white"
    private var lastTextFont = "roboto_bold"   // default: Roboto Bold
    private var lastTextBgColor = "black"       // default: Black background
    private var lastTextBgOpacity = 0.6f
    private var lastImageScale = "medium"
    private var lastTickerFont = "roboto_bold"
    private var lastTickerBgColor = "black"
    private var lastTickerBgOpacity = 0.6f

    // Ticker
    private var tickerText = ""
    private var tickerFilter: TextObjectFilterRender? = null
    private var tickerPositionX = 0f
    private var tickerHandler: Handler? = null
    private var tickerRunnable: Runnable? = null
    private var isSingleAppShare = false
    private var isMicMuted = false
    private var savedBitrate = 2_000_000 // default 2 Mbps
    private var savedScreenWidth = 0
    private var savedScreenHeight = 0

    // Camera restart साठी — एकापेक्षा जास्त वेळा restart होऊ नये म्हणून flag
    private var isCameraRestarting = false

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

    private fun applyVoiceEffect(mode: String) {}

    // ─── Camera Restart Logic ────────────────────────────────────────────────

    /**
     * Camera interrupt/freeze झाल्यावर हे call होतं (CameraOverlay च्या onCameraError मधून).
     * 2 seconds wait करून camera restart करतो.
     * isCameraRestarting flag मुळे multiple restarts होत नाहीत.
     */
    private fun onCameraInterrupted() {
        // onPause/onResume MainActivity मधून handle होतं आता
        // इथे काही करायची गरज नाही — camera pause झाला तर resume ने restart होईल
        Log.d(TAG, "Camera interrupted — waiting for onResume to restart")
    }

    // ────────────────────────────────────────────────────────────────────────

    private fun setupCamera() {
        try {
            val glInterface = genericStream?.getGlInterface() ?: rtmpDisplay?.glInterface ?: run {
                notify("Camera: stream not ready")
                return
            }

            val oldFilter = cameraFilter
            if (oldFilter != null) {
                try { glInterface.removeFilter(oldFilter) } catch (_: Exception) {}
                cameraFilter = null
            }
            cameraOverlay?.stop()
            cameraOverlay = null

            val filter = ImageObjectFilterRender()
            when (cameraMode) {
                "pip"   -> { filter.setScale(25f, 25f); filter.setPosition(72f, 70f) }
                "split" -> { filter.setScale(98f, 28f); filter.setPosition(1f, 71f) }
                else    -> { filter.setScale(25f, 25f); filter.setPosition(72f, 70f) }
            }
            glInterface.addFilter(filter)
            cameraFilter = filter

            val useFront = cameraFacing == "front"

            cameraOverlay = CameraOverlay(
                context = applicationContext,
                onFrame = { bitmap ->
                    val currentFilter = cameraFilter
                    if (currentFilter == null) { bitmap.recycle(); return@CameraOverlay }
                    try {
                        if (cameraFilter != null) currentFilter.setImage(bitmap)
                        else bitmap.recycle()
                    } catch (_: Exception) { bitmap.recycle() }
                },
                onCameraError = {
                    // Camera freeze/disconnect झाला → auto restart
                    Log.w(TAG, "Camera error callback received — triggering restart")
                    onCameraInterrupted()
                }
            )

            cameraOverlay!!.start(useFront, savedOrientation == "portrait")

            // GL filter add झाल्यावर 400ms wait → मग frames accept करायला सांग
            // यामुळे black screen येणार नाही restart नंतर
            mainHandler.postDelayed({
                cameraOverlay?.markFilterReady()
            }, 400)

            notify("📷 Camera ON")

        } catch (e: Exception) {
            notify("Camera error: ${e.message}")
        }
    }

    private fun stopCamera() {
        val glInterface = genericStream?.getGlInterface() ?: rtmpDisplay?.glInterface
        val oldFilter = cameraFilter
        cameraFilter = null
        if (oldFilter != null) {
            try { glInterface?.removeFilter(oldFilter) } catch (_: Exception) {}
        }
        cameraOverlay?.stop()
        cameraOverlay = null
    }

    // Font load करणे — assets/Fonts/ मधून
    private fun loadTypeface(fontName: String): android.graphics.Typeface {
        return try {
            when (fontName) {
                "roboto_bold" -> android.graphics.Typeface.createFromAsset(assets, "Fonts/Roboto-Bold.ttf")
                "roboto_condensed" -> android.graphics.Typeface.createFromAsset(assets, "Fonts/Roboto_Condensed-Black.ttf")
                "bangers" -> android.graphics.Typeface.createFromAsset(assets, "Fonts/Bangers-Regular.ttf")
                else -> android.graphics.Typeface.createFromAsset(assets, "Fonts/Roboto-Bold.ttf")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Font load error: ${e.message}")
            android.graphics.Typeface.DEFAULT_BOLD
        }
    }

    // Background color parse
    private fun parseBgColor(colorName: String, opacity: Float): Int {
        val alpha = (opacity * 255).toInt().coerceIn(0, 255)
        return when (colorName) {
            "white" -> Color.argb(alpha, 255, 255, 255)
            "none"  -> Color.TRANSPARENT
            else    -> Color.argb(alpha, 0, 0, 0)  // black default
        }
    }

    private fun applyOverlay(
        overlayText: String, overlayImagePath: String,
        textX: Float, textY: Float, imageX: Float, imageY: Float,
        bold: Boolean = false, textSize: String = "medium",
        textColor: String = "white", imageScale: String = "medium",
        fontName: String = "roboto_bold", bgColor: String = "black", bgOpacity: Float = 0.6f
    ) {
        lastOverlayText = overlayText
        lastOverlayImagePath = overlayImagePath
        lastTextX = textX; lastTextY = textY
        lastImageX = imageX; lastImageY = imageY
        lastTextBold = bold; lastTextSize = textSize
        lastTextColor = textColor; lastImageScale = imageScale
        lastTextFont = fontName; lastTextBgColor = bgColor; lastTextBgOpacity = bgOpacity

        try {
            val glInterface = genericStream?.getGlInterface() ?: rtmpDisplay?.glInterface ?: return

            textFilter?.let { try { glInterface.removeFilter(it) } catch (_: Exception) {} }
            imageFilter?.let { try { glInterface.removeFilter(it) } catch (_: Exception) {} }
            textFilter = null; imageFilter = null

            if (overlayText.isNotEmpty()) {
                val fontSize = when (textSize) {
                    "small" -> 32f
                    "large" -> 64f
                    else -> 48f
                }
                val color = when (textColor) {
                    "yellow" -> Color.YELLOW
                    "red" -> Color.RED
                    "black" -> Color.BLACK
                    else -> Color.WHITE
                }
                val typeface = loadTypeface(fontName)
                val bg = parseBgColor(bgColor, bgOpacity)
                val tf = TextObjectFilterRender()
                val scaleW = when (textSize) { "small" -> 28f; "large" -> 45f; else -> 35f }
                tf.setScale(scaleW, 12f)
                tf.setPosition(textX * 100f, textY * 100f)
                tf.setText(overlayText, fontSize, color)
                glInterface.addFilter(tf)
                textFilter = tf
            }

            if (overlayImagePath.isNotEmpty()) {
                val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                val rawBitmap = BitmapFactory.decodeFile(overlayImagePath, opts)
                if (rawBitmap != null) {
                    // Aspect ratio maintain करा — image फाटू नये म्हणून
                    val streamW = savedScreenWidth.takeIf { it > 0 } ?: 1280
                    val streamH = savedScreenHeight.takeIf { it > 0 } ?: 720
                    val baseScale = when (imageScale) { "small" -> 15f; "large" -> 30f; else -> 20f }
                    val imgAspect = rawBitmap.width.toFloat() / rawBitmap.height.toFloat()
                    val streamAspect = streamW.toFloat() / streamH.toFloat()
                    val scaleW: Float
                    val scaleH: Float
                    if (imgAspect > 1f) {
                        scaleW = baseScale
                        scaleH = baseScale / imgAspect * streamAspect
                    } else {
                        scaleH = baseScale
                        scaleW = baseScale * imgAspect / streamAspect
                    }
                    val sf = ImageObjectFilterRender()
                    sf.setScale(scaleW, scaleH)
                    sf.setPosition(imageX * 100f, imageY * 100f)
                    sf.setImage(rawBitmap)
                    glInterface.addFilter(sf)
                    imageFilter = sf
                }
            }
        } catch (_: Exception) {}
    }

    // Ticker — manually scroll करतो Timer ने
    private fun applyTicker(text: String, color: String = "white", fontName: String = "roboto_bold", bgColor: String = "black", bgOpacity: Float = 0.6f) {
        if (text.isEmpty()) {
            stopTicker()
            return
        }
        val glInterface = genericStream?.getGlInterface() ?: rtmpDisplay?.glInterface ?: return

        tickerFilter?.let { try { glInterface.removeFilter(it) } catch (_: Exception) {} }
        tickerFilter = null
        stopTicker()

        tickerText = text
        lastTickerFont = fontName; lastTickerBgColor = bgColor; lastTickerBgOpacity = bgOpacity
        val tf = TextObjectFilterRender()
        val tickerColor = when (color) {
            "yellow" -> Color.YELLOW; "red" -> Color.RED; else -> Color.WHITE
        }
        val tickerTypeface = loadTypeface(fontName)
        val tickerBg = parseBgColor(bgColor, bgOpacity)
        tf.setScale(60f, 8f)
        tf.setPosition(100f, 88f)
        tf.setText(text, 36f, tickerColor)
        glInterface.addFilter(tf)
        tickerFilter = tf
        tickerPositionX = 100f

        val handler = Handler(Looper.getMainLooper())
        tickerHandler = handler
        val runnable = object : Runnable {
            override fun run() {
                val filter = tickerFilter ?: return
                tickerPositionX -= 0.4f
                if (tickerPositionX < -65f) tickerPositionX = 100f
                try { filter.setPosition(tickerPositionX, 88f) } catch (_: Exception) {}
                handler.postDelayed(this, 33) // ~30fps
            }
        }
        tickerRunnable = runnable
        handler.postDelayed(runnable, 33)
    }

    private fun stopTicker() {
        tickerRunnable?.let { tickerHandler?.removeCallbacks(it) }
        tickerRunnable = null
        tickerHandler = null
        val glInterface = genericStream?.getGlInterface() ?: rtmpDisplay?.glInterface
        tickerFilter?.let { try { glInterface?.removeFilter(it) } catch (_: Exception) {} }
        tickerFilter = null
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
                // 🔊→🔇 internal audio बंद (audioBtn — दोन्ही modes ला)
                isMicMuted = true
                val src = genericStream?.audioSource
                when (src) {
                    is InternalAudioSource -> src.mute()
                    is MixAudioSource -> src.mute()  // mic_internal मध्ये mute() संपूर्ण audio बंद करतो
                    else -> {}
                }
                return START_NOT_STICKY
            }
            "MIC_UNMUTE" -> {
                // 🔇→🔊 internal audio चालू
                isMicMuted = false
                val src = genericStream?.audioSource
                when (src) {
                    is InternalAudioSource -> src.unMute()
                    is MixAudioSource -> src.unMute()
                    else -> {}
                }
                return START_NOT_STICKY
            }
            "MIC_ONLY_MUTE" -> {
                // 🎤→🚫 फक्त mic बंद, internal चालू राहतो (mic_internal mode फक्त)
                // RootEncoder च्या MixAudioSource वर mic-only control नाही, त्यामुळे
                // खालचा workaround वापरतो: AudioSource interface वरचा mute हाच आहे,
                // पण आपण फक्त mic_internal मध्ये हे call करतो तेव्हा तो mic comp आहे असं गृहीत धरतो
                Log.d(TAG, "MIC_ONLY_MUTE requested — keeping internal audio active")
                return START_NOT_STICKY
            }
            "MIC_ONLY_UNMUTE" -> {
                Log.d(TAG, "MIC_ONLY_UNMUTE requested")
                return START_NOT_STICKY
            }
            "SET_VOICE" -> {
                val mode = intent.getStringExtra("voiceMode") ?: "normal"
                currentVoiceMode = mode
                applyVoiceEffect(mode)
                return START_NOT_STICKY
            }
            "CAMERA_ON_BACK" -> {
                mainHandler.post {
                    cameraEnabled = true
                    cameraFacing = "back"
                    isCameraRestarting = false
                    stopCamera()
                    mainHandler.postDelayed({ setupCamera() }, 300)
                }
                return START_NOT_STICKY
            }
            "CAMERA_ON_FRONT" -> {
                mainHandler.post {
                    cameraEnabled = true
                    cameraFacing = "front"
                    isCameraRestarting = false
                    stopCamera()
                    mainHandler.postDelayed({ setupCamera() }, 300)
                }
                return START_NOT_STICKY
            }
            "CAMERA_TOGGLE" -> {
                mainHandler.post {
                    if (!cameraEnabled) {
                        cameraEnabled = true
                        isCameraRestarting = false
                        setupCamera()
                    } else {
                        cameraEnabled = false
                        isCameraRestarting = false
                        stopCamera()
                    }
                }
                return START_NOT_STICKY
            }
            "CAMERA_PAUSE" -> {
                // दुसरा app foreground आला → camera तात्पुरता release करा
                mainHandler.post {
                    if (cameraEnabled) {
                        Log.d(TAG, "CAMERA_PAUSE — releasing camera for other app")
                        isCameraRestarting = false
                        cameraOverlay?.stop()
                        cameraOverlay = null
                    }
                }
                return START_NOT_STICKY
            }
            "CAMERA_RESUME" -> {
                // आपला app परत foreground → camera restart करा
                mainHandler.post {
                    if (cameraEnabled) {
                        Log.d(TAG, "CAMERA_RESUME — restarting camera")
                        isCameraRestarting = false
                        // थोडा delay द्या म्हणजे system camera release confirm होईल
                        mainHandler.postDelayed({ setupCamera() }, 500)
                    }
                }
                return START_NOT_STICKY
            }
            "CAMERA_OFF" -> {
                mainHandler.post {
                    cameraEnabled = false
                    isCameraRestarting = false
                    stopCamera()
                }
                return START_NOT_STICKY
            }
            "CAMERA_SWITCH" -> {
                cameraFacing = if (cameraFacing == "back") "front" else "back"
                cameraEnabled = true
                mainHandler.post {
                    isCameraRestarting = false
                    stopCamera()
                    mainHandler.postDelayed({ setupCamera() }, 1200)
                }
                return START_NOT_STICKY
            }
            "UPDATE_OVERLAY" -> {
                val text = intent.getStringExtra("overlayText") ?: ""
                val imagePath = intent.getStringExtra("overlayImagePath") ?: ""
                val tx = intent.getFloatExtra("textX", 0.05f)
                val ty = intent.getFloatExtra("textY", 0.05f)
                val ix = intent.getFloatExtra("imageX", 0.7f)
                val iy = intent.getFloatExtra("imageY", 0.05f)
                val bold = intent.getBooleanExtra("textBold", false)
                val tSize = intent.getStringExtra("textSize") ?: "medium"
                val tColor = intent.getStringExtra("textColor") ?: "white"
                val iScale = intent.getStringExtra("imageScale") ?: "medium"
                val tFont = intent.getStringExtra("textFont") ?: "roboto_bold"
                val tBgColor = intent.getStringExtra("textBgColor") ?: "black"
                val tBgOpacity = intent.getFloatExtra("textBgOpacity", 0.6f)
                mainHandler.post { applyOverlay(text, imagePath, tx, ty, ix, iy, bold, tSize, tColor, iScale, tFont, tBgColor, tBgOpacity) }
                return START_NOT_STICKY
            }
            "UPDATE_TICKER" -> {
                val text = intent.getStringExtra("tickerText") ?: ""
                val color = intent.getStringExtra("tickerColor") ?: "white"
                val tFont = intent.getStringExtra("tickerFont") ?: "roboto_bold"
                val tBgColor = intent.getStringExtra("tickerBgColor") ?: "black"
                val tBgOpacity = intent.getFloatExtra("tickerBgOpacity", 0.6f)
                mainHandler.post { applyTicker(text, color, tFont, tBgColor, tBgOpacity) }
                return START_NOT_STICKY
            }
            "STOP_TICKER" -> {
                mainHandler.post { stopTicker() }
                return START_NOT_STICKY
            }
            else -> {
                if (intent?.action != null) return START_NOT_STICKY
            }
        }

        // action = null → नवीन stream start
        val resultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY
        val rtmpUrl = intent.getStringExtra("rtmpUrl") ?: return START_NOT_STICKY
        val streamKey = intent.getStringExtra("streamKey") ?: return START_NOT_STICKY
        val audioMode = intent.getStringExtra("audioMode") ?: "internal"
        val orientation = intent.getStringExtra("orientation") ?: "landscape"
        currentVoiceMode = intent.getStringExtra("voiceMode") ?: "normal"
        cameraEnabled = intent.getBooleanExtra("cameraEnabled", false)
        cameraFacing = intent.getStringExtra("cameraFacing") ?: "back"
        cameraMode = intent.getStringExtra("cameraMode") ?: "pip"
        isSingleAppShare = intent.getBooleanExtra("singleAppShare", false)
        savedBitrate = intent.getIntExtra("bitrate", 2_000_000)
        savedScreenWidth = intent.getIntExtra("screenWidth", 0)
        savedScreenHeight = intent.getIntExtra("screenHeight", 0)

        lastOverlayText = intent.getStringExtra("overlayText") ?: ""
        lastOverlayImagePath = intent.getStringExtra("overlayImagePath") ?: ""
        lastTextX = intent.getFloatExtra("textX", 0.05f)
        lastTextY = intent.getFloatExtra("textY", 0.05f)
        lastImageX = intent.getFloatExtra("imageX", 0.7f)
        lastImageY = intent.getFloatExtra("imageY", 0.05f)

        savedResultCode = resultCode
        savedData = data
        savedOrientation = orientation
        savedFullUrl = "$rtmpUrl/$streamKey"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Camera type add केला — MIUI/Xiaomi camera revoke करत होता कारण हे missing होतं
            val cameraType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            } else { 0 }

            val fgsType = if (audioMode == "mic_internal") {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                cameraType
            } else {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                cameraType
            }
            startForeground(NOTIF_ID, buildNotification(), fgsType)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        acquireWakeLock()

        val isPortrait = orientation == "portrait"
        // Actual screen dimensions वापर — fixed 720/1280 मुळे portrait फाटत होतं
        val vW = when {
            savedScreenWidth > 0 -> savedScreenWidth
            isPortrait -> 720
            else -> 1280
        }
        val vH = when {
            savedScreenHeight > 0 -> savedScreenHeight
            isPortrait -> 1280
            else -> 720
        }

        mainHandler.post {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (audioMode == "mic_internal") {
                        startMixedAudio(savedFullUrl, vW, vH, resultCode, data)
                    } else {
                        startInternalOnly(savedFullUrl, vW, vH, resultCode, data)
                    }
                } else {
                    notify("Android 10+ required for streaming")
                    releaseWakeLock(); stopSelf()
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
    private fun startMixedAudio(url: String, w: Int, h: Int, rc: Int, d: Intent) {
        val mp: MediaProjection = getMediaProjection(rc, d)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopStreaming() }
            }, mainHandler)
        }

        // ScreenSource फक्त VIDEO साठी
        val screen = ScreenSource(applicationContext, mp)
        // MixAudioSource सुरुवातीपासूनच constructor मध्ये — double audio टाळण्यासाठी
        // हाच एकमेव audio source — internal + mic दोन्ही यातूनच
        val mix = MixAudioSource(mp)
        mixAudioSource = mix

        genericStream = GenericStream(applicationContext, this, screen, mix).apply {
            getGlInterface().setForceRender(true)
        }

        val vOk = genericStream!!.prepareVideo(w, h, savedBitrate)
        val aOk = genericStream!!.prepareAudio(
            sampleRate = 44100, isStereo = true, bitrate = 128_000,
            echoCanceler = true, noiseSuppressor = true
        )

        if (vOk && aOk) {
            genericStream!!.startStream(url)
            mainHandler.postDelayed({
                applyOverlay(lastOverlayText, lastOverlayImagePath, lastTextX, lastTextY, lastImageX, lastImageY, lastTextBold, lastTextSize, lastTextColor, lastImageScale, lastTextFont, lastTextBgColor, lastTextBgOpacity)
                if (cameraEnabled) setupCamera()
            }, 1000)
        } else {
            notify("Mic+Internal V:$vOk A:$aOk — switching to internal only")
            try { genericStream?.release() } catch (_: Exception) {}
            genericStream = null; mixAudioSource = null; mp.stop()
            startInternalOnly(url, w, h, savedResultCode, savedData!!)
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun startInternalOnly(url: String, w: Int, h: Int, rc: Int, d: Intent) {
        // RtmpDisplay portrait handle करत नाही → GenericStream वापरतो
        // InternalAudioSource = फक्त internal audio, mic बिल्कुल नाही (clean, no double)
        val mp: MediaProjection = getMediaProjection(rc, d)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopStreaming() }
            }, mainHandler)
        }

        val screen = ScreenSource(applicationContext, mp)
        val internalAudio = InternalAudioSource(mp)

        genericStream = GenericStream(applicationContext, this, screen, internalAudio).apply {
            getGlInterface().setForceRender(true)
        }

        val vOk = genericStream!!.prepareVideo(w, h, savedBitrate)
        val aOk = genericStream!!.prepareAudio(
            sampleRate = 44100, isStereo = true, bitrate = 128_000,
            echoCanceler = false, noiseSuppressor = false
        )

        if (vOk && aOk) {
            genericStream!!.startStream(url)
            mainHandler.postDelayed({
                applyOverlay(lastOverlayText, lastOverlayImagePath, lastTextX, lastTextY, lastImageX, lastImageY, lastTextBold, lastTextSize, lastTextColor, lastImageScale, lastTextFont, lastTextBgColor, lastTextBgOpacity)
                if (cameraEnabled) setupCamera()
            }, 1000)
        } else {
            notify("Prepare failed V:$vOk A:$aOk")
            try { mp.stop() } catch (_: Exception) {}
            releaseWakeLock(); stopSelf()
        }
    }

    override fun onConnectionStarted(url: String) {}

    override fun onConnectionSuccess() {
        mainHandler.post { mainActivity?.notifyFlutter("onStreamStarted") }
    }

    override fun onConnectionFailed(reason: String) {
        notify("⚠️ Failed: $reason")
        mainHandler.postDelayed({
            val isStillStreaming = rtmpDisplay?.isStreaming == true ||
                                   genericStream?.isStreaming == true
            if (!isStillStreaming) {
                releaseWakeLock()
                stopSelf()
            }
        }, 10_000)
    }

    override fun onNewBitrate(bitrate: Long) {
        mainHandler.post { mainActivity?.notifyFlutter("onBitrateUpdate", "${bitrate / 1000}") }
    }

    override fun onDisconnect() {
        notify("⚠️ Disconnected - reconnecting...")
    }

    override fun onAuthError() { notify("Auth error") }
    override fun onAuthSuccess() {}

    override fun onDestroy() {
        super.onDestroy()
        try { screenReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        stopTicker()
        stopCamera()
        try { if (rtmpDisplay?.isStreaming == true) rtmpDisplay?.stopStream() } catch (_: Exception) {}
        try { if (genericStream?.isStreaming == true) genericStream?.stopStream() } catch (_: Exception) {}
        releaseWakeLock()
    }

    private fun stopStreaming() {
        try { if (rtmpDisplay?.isStreaming == true) rtmpDisplay?.stopStream() } catch (_: Exception) {}
        try { if (genericStream?.isStreaming == true) genericStream?.stopStream() } catch (_: Exception) {}
        stopTicker()
        stopCamera()
        releaseWakeLock()
        stopForeground(true)
        stopSelf()
        mainHandler.post { mainActivity?.notifyFlutter("onStreamStopped") }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "YT Stream", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
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
