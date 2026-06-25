package com.mango.ytstream

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
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
    private var isMuted = false
    private var isMicMuted = false
    private lateinit var pauseBtn: TextView
    private lateinit var muteBtn: TextView
    private lateinit var micBtn: TextView

    override fun onBind(intent: Intent?): IBinder? = null

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

        // Logo — app icon किंवा YT icon
        val logoView = makeLogoView()

        pauseBtn = makeBtn("⏸", Color.argb(255, 200, 120, 0))
        muteBtn = makeBtn("🔊", Color.argb(255, 0, 100, 180))
        micBtn = makeBtn("🎤", Color.argb(255, 0, 130, 80))
        // Camera toggle
cameraBtn.setOnClickListener {
    send("CAMERA_TOGGLE")
}
        val camBtn = makeBtn("📷", Color.argb(255, 80, 0, 150))
        val stopBtn = makeBtn("⏹", Color.argb(255, 200, 0, 0))

        container.addView(logoView)
        container.addView(spacer())
        container.addView(pauseBtn)
        container.addView(spacer())
        container.addView(muteBtn)
        container.addView(spacer())
        container.addView(micBtn)
        container.addView(spacer())
        container.addView(camBtn)
        container.addView(spacer())
        container.addView(stopBtn)

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
            // Gravity.START वापरतो — drag बरोबर होण्यासाठी
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = 200
        }

        windowManager!!.addView(floatingView, params)

        // Drag — START gravity मुळे x position बरोबर calculate होते
        var ix = 0; var iy = 0; var tx = 0f; var ty = 0f

        floatingView!!.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    ix = params.x; iy = params.y
                    tx = e.rawX; ty = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = ix + (e.rawX - tx).toInt()
                    params.y = iy + (e.rawY - ty).toInt()
                    // Screen bounds च्या बाहेर जाऊ देत नाही
                    params.x = params.x.coerceAtLeast(0)
                    params.y = params.y.coerceAtLeast(0)
                    windowManager!!.updateViewLayout(floatingView, params)
                    true
                }
                else -> false
            }
        }

        // Pause/Resume
        pauseBtn.setOnClickListener {
            if (!isPaused) {
                send("PAUSE")
                isPaused = true
                pauseBtn.text = "▶"
                setBtnColor(pauseBtn, Color.argb(255, 0, 160, 0))
            } else {
                send("RESUME")
                isPaused = false
                isMuted = false
                isMicMuted = false
                pauseBtn.text = "⏸"
                setBtnColor(pauseBtn, Color.argb(255, 200, 120, 0))
                muteBtn.text = "🔊"
                setBtnColor(muteBtn, Color.argb(255, 0, 100, 180))
                micBtn.text = "🎤"
                setBtnColor(micBtn, Color.argb(255, 0, 130, 80))
            }
        }

        // Mute/Unmute
        muteBtn.setOnClickListener {
            if (!isMuted) {
                send("MUTE")
                isMuted = true
                muteBtn.text = "🔇"
                setBtnColor(muteBtn, Color.argb(255, 150, 0, 0))
            } else {
                send("UNMUTE")
                isMuted = false
                muteBtn.text = "🔊"
                setBtnColor(muteBtn, Color.argb(255, 0, 100, 180))
            }
        }

        // Mic Mute
        micBtn.setOnClickListener {
            if (!isMicMuted) {
                send("MIC_MUTE")
                isMicMuted = true
                micBtn.text = "🚫"
                setBtnColor(micBtn, Color.argb(255, 150, 50, 0))
            } else {
                send("MIC_UNMUTE")
                isMicMuted = false
                micBtn.text = "🎤"
                setBtnColor(micBtn, Color.argb(255, 0, 130, 80))
            }
        }

        // Camera toggle
        var isCamOn = false
        camBtn.setOnClickListener {
            if (!isCamOn) {
                send("CAMERA_ON")
                isCamOn = true
                camBtn.text = "🎥"
                setBtnColor(camBtn, Color.argb(255, 0, 100, 200))
            } else {
                send("CAMERA_OFF")
                isCamOn = false
                camBtn.text = "📷"
                setBtnColor(camBtn, Color.argb(255, 80, 0, 150))
            }
        }

        // Long press camera = switch front/back
        camBtn.setOnLongClickListener {
            send("CAMERA_SWITCH")
            true
        }

        // Stop
        stopBtn.setOnClickListener {
            send("STOP")
            stopSelf()
        }
    }

    // Logo view — app launcher icon वापरतो
    private fun makeLogoView(): ImageView {
        val iv = ImageView(this)
        try {
            val icon = packageManager.getApplicationIcon(packageName)
            iv.setImageDrawable(icon)
        } catch (_: Exception) {
            // Icon नाही मिळाला तर लाल circle दाखवतो
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
        try { if (floatingView != null) windowManager!!.removeView(floatingView) } catch (_: Exception) {}
    }
}
