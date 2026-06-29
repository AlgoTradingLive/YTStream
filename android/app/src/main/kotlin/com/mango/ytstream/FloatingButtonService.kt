package com.mango.ytstream

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class FloatingButtonService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var isPaused = false
    private var isMicMuted = false
    private var isMicOnlyMuted = false

    // 0 = OFF, 1 = Back ON, 2 = Front ON
    private var camState = 0
    private var audioMode = "internal"
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var pauseBtn: TextView
    private lateinit var audioBtn: TextView  // internal audio 🔊/🔇
    private lateinit var micBtn: TextView    // mic only 🎤/🚫 (mic_internal mode साठी)
    private lateinit var camBtn: TextView

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        audioMode = intent?.getStringExtra("audioMode") ?: "internal"
        if (::micBtn.isInitialized) {
            // mic_internal mode असेल तरच 🎤 button दाखव
            micBtn.visibility = if (audioMode == "mic_internal") View.VISIBLE else View.GONE
        }
        return START_NOT_STICKY
    }

    private fun updateCamBtn() {
        when (camState) {
            0 -> { camBtn.text = "📷❌"; setBtnColor(camBtn, Color.argb(255, 80, 0, 150)) }
            1 -> { camBtn.text = "📷B"; setBtnColor(camBtn, Color.argb(255, 0, 130, 0)) }
            2 -> { camBtn.text = "📷F"; setBtnColor(camBtn, Color.argb(255, 0, 100, 200)) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(10, 6, 10, 6)
            background = GradientDrawable().apply {
                setColor(Color.argb(210, 20, 20, 20))
                cornerRadius = 60f
            }
        }

        val logoView = makeLogoView()
        pauseBtn  = makeBtn("⏸", Color.argb(255, 200, 120, 0))
        audioBtn  = makeBtn("🔊", Color.argb(255, 0, 100, 180))  // internal audio on/off
        micBtn    = makeBtn("🎤", Color.argb(255, 0, 130, 80))   // mic on/off (mic_internal only)
        camBtn    = makeBtn("📷", Color.argb(255, 80, 0, 150))
        val stopBtn = makeBtn("⏹", Color.argb(255, 200, 0, 0))

        container.addView(logoView)
        container.addView(spacer())
        container.addView(pauseBtn)
        container.addView(spacer())
        container.addView(audioBtn)  // नेहमी दिसतो
        container.addView(spacer())
        container.addView(micBtn)    // फक्त mic_internal ला दिसेल
        container.addView(spacer())
        container.addView(camBtn)
        container.addView(spacer())
        container.addView(stopBtn)

        // Default: micBtn hidden — onStartCommand मध्ये audioMode मिळाल्यावर update होईल
        micBtn.visibility = View.GONE
        updateCamBtn()

        floatingView = container

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = 200
        }

        windowManager!!.addView(floatingView, params)

        var ix = 0; var iy = 0; var tx = 0f; var ty = 0f
        floatingView!!.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    ix = params.x; iy = params.y
                    tx = e.rawX; ty = e.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (ix + (e.rawX - tx).toInt()).coerceAtLeast(0)
                    params.y = (iy + (e.rawY - ty).toInt()).coerceAtLeast(0)
                    windowManager!!.updateViewLayout(floatingView, params); true
                }
                else -> false
            }
        }

        pauseBtn.setOnClickListener {
            if (!isPaused) {
                send("PAUSE"); isPaused = true
                pauseBtn.text = "▶"
                setBtnColor(pauseBtn, Color.argb(255, 0, 160, 0))
            } else {
                send("RESUME"); isPaused = false
                isMicMuted = false; isMicOnlyMuted = false
                pauseBtn.text = "⏸"
                setBtnColor(pauseBtn, Color.argb(255, 200, 120, 0))
                audioBtn.text = "🔊"; setBtnColor(audioBtn, Color.argb(255, 0, 100, 180))
                micBtn.text = "🎤"; setBtnColor(micBtn, Color.argb(255, 0, 130, 80))
            }
        }

        // 🔊 — internal audio mute/unmute
        audioBtn.setOnClickListener {
            if (!isMicMuted) {
                send("MIC_MUTE"); isMicMuted = true
                audioBtn.text = "🔇"
                setBtnColor(audioBtn, Color.argb(255, 150, 0, 0))
            } else {
                send("MIC_UNMUTE"); isMicMuted = false
                audioBtn.text = "🔊"
                setBtnColor(audioBtn, Color.argb(255, 0, 100, 180))
            }
        }

        // 🎤 — mic only mute/unmute (mic_internal mode साठी)
        micBtn.setOnClickListener {
            if (!isMicOnlyMuted) {
                send("MIC_ONLY_MUTE"); isMicOnlyMuted = true
                micBtn.text = "🚫"
                setBtnColor(micBtn, Color.argb(255, 150, 50, 0))
            } else {
                send("MIC_ONLY_UNMUTE"); isMicOnlyMuted = false
                micBtn.text = "🎤"
                setBtnColor(micBtn, Color.argb(255, 0, 130, 80))
            }
        }

        camBtn.setOnClickListener {
            camBtn.isEnabled = false
            camState = (camState + 1) % 3
            updateCamBtn()
            when (camState) {
                0 -> send("CAMERA_OFF")
                1 -> send("CAMERA_ON_BACK")
                2 -> send("CAMERA_SWITCH")
            }
            mainHandler.postDelayed({ camBtn.isEnabled = true }, 1500)
        }

        stopBtn.setOnClickListener {
            send("STOP"); stopSelf()
        }
    }

    private fun makeLogoView(): ImageView {
        val iv = ImageView(this)
        try {
            iv.setImageDrawable(packageManager.getApplicationIcon(packageName))
        } catch (_: Exception) {
            val bmp = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val paint = Paint().apply { color = Color.RED; isAntiAlias = true }
            canvas.drawOval(RectF(4f, 4f, 44f, 44f), paint)
            iv.setImageBitmap(bmp)
        }
        val size = (44 * resources.displayMetrics.density).toInt()
        iv.layoutParams = LinearLayout.LayoutParams(size, size).apply {
            gravity = Gravity.CENTER_VERTICAL
            marginEnd = 4
        }
        iv.setPadding(4, 4, 4, 4)
        return iv
    }

    private fun send(action: String) {
        startService(Intent(applicationContext, StreamService::class.java).apply {
            this.action = action
        })
    }

    private fun makeBtn(text: String, color: Int) = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(color)
            cornerRadius = 40f
        }
        setPadding(18, 12, 18, 12)
        gravity = Gravity.CENTER
    }

    private fun spacer() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(10, 1)
    }

    private fun setBtnColor(btn: TextView, color: Int) {
        (btn.background as GradientDrawable).setColor(color)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (floatingView != null) windowManager!!.removeView(floatingView)
        } catch (_: Exception) {}
    }
}
