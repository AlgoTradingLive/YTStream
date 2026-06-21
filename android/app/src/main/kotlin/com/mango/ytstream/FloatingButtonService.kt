package com.mango.ytstream

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

class FloatingButtonService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var isPaused = false
    private var isMuted = false
    private lateinit var pauseBtn: TextView
    private lateinit var muteBtn: TextView

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 8, 12, 8)
            background = GradientDrawable().apply {
                setColor(Color.argb(210, 20, 20, 20))
                cornerRadius = 60f
            }
        }

        pauseBtn = makeBtn("⏸", Color.argb(255, 200, 120, 0))
        muteBtn = makeBtn("🔊", Color.argb(255, 0, 100, 180))
        val stopBtn = makeBtn("⏹", Color.argb(255, 200, 0, 0))

        container.addView(pauseBtn)
        container.addView(spacer())
        container.addView(muteBtn)
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
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 200
        }

        windowManager!!.addView(floatingView, params)

        // Drag
        var ix = 0; var iy = 0; var tx = 0f; var ty = 0f

        floatingView!!.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { ix = params.x; iy = params.y; tx = e.rawX; ty = e.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    params.x = ix - (e.rawX - tx).toInt()
                    params.y = iy + (e.rawY - ty).toInt()
                    windowManager!!.updateViewLayout(floatingView, params)
                    true
                }
                else -> false
            }
        }

        // Pause/Resume
        pauseBtn.setOnClickListener {
            if (!isPaused) {
                startService(Intent(applicationContext, StreamService::class.java).apply { action = "PAUSE" })
                isPaused = true
                pauseBtn.text = "▶"
                setBtnColor(pauseBtn, Color.argb(255, 0, 160, 0))
            } else {
                startService(Intent(applicationContext, StreamService::class.java).apply { action = "RESUME" })
                isPaused = false
                pauseBtn.text = "⏸"
                setBtnColor(pauseBtn, Color.argb(255, 200, 120, 0))
                // Resume मुळे mute state clear होतो
                if (isMuted) {
                    isMuted = false
                    muteBtn.text = "🔊"
                    setBtnColor(muteBtn, Color.argb(255, 0, 100, 180))
                }
            }
        }

        // Mute/Unmute
        muteBtn.setOnClickListener {
            if (!isMuted) {
                startService(Intent(applicationContext, StreamService::class.java).apply { action = "MUTE" })
                isMuted = true
                muteBtn.text = "🔇"
                setBtnColor(muteBtn, Color.argb(255, 150, 0, 0))
            } else {
                startService(Intent(applicationContext, StreamService::class.java).apply { action = "UNMUTE" })
                isMuted = false
                muteBtn.text = "🔊"
                setBtnColor(muteBtn, Color.argb(255, 0, 100, 180))
            }
        }

        // Stop
        stopBtn.setOnClickListener {
            startService(Intent(applicationContext, StreamService::class.java).apply { action = "STOP" })
            stopSelf()
        }
    }

    private fun makeBtn(text: String, color: Int) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(color)
            cornerRadius = 40f
        }
        setPadding(22, 14, 22, 14)
        gravity = Gravity.CENTER
    }

    private fun spacer() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(12, 1)
    }

    private fun setBtnColor(btn: TextView, color: Int) {
        (btn.background as GradientDrawable).setColor(color)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { if (floatingView != null) windowManager!!.removeView(floatingView) } catch (_: Exception) {}
    }
}
