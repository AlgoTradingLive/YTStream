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
    private lateinit var pauseBtn: TextView

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

        pauseBtn = TextView(this).apply {
            text = "⏸"
            textSize = 18f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.argb(255, 200, 120, 0))
                cornerRadius = 40f
            }
            setPadding(24, 14, 24, 14)
            gravity = Gravity.CENTER
        }

        val stopBtn = TextView(this).apply {
            text = "⏹"
            textSize = 18f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.argb(255, 200, 0, 0))
                cornerRadius = 40f
            }
            setPadding(24, 14, 24, 14)
            gravity = Gravity.CENTER
        }

        val space = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(16, 1)
        }

        container.addView(pauseBtn)
        container.addView(space)
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
        var ix = 0; var iy = 0; var tx = 0f; var ty = 0f; var drag = false

        floatingView!!.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { ix = params.x; iy = params.y; tx = e.rawX; ty = e.rawY; drag = false; true }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(e.rawX - tx) > 5 || Math.abs(e.rawY - ty) > 5) drag = true
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
                (pauseBtn.background as GradientDrawable).setColor(Color.argb(255, 0, 160, 0))
            } else {
                startService(Intent(applicationContext, StreamService::class.java).apply { action = "RESUME" })
                isPaused = false
                pauseBtn.text = "⏸"
                (pauseBtn.background as GradientDrawable).setColor(Color.argb(255, 200, 120, 0))
            }
        }

        // Stop
        stopBtn.setOnClickListener {
            startService(Intent(applicationContext, StreamService::class.java).apply { action = "STOP" })
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { if (floatingView != null) windowManager!!.removeView(floatingView) } catch (_: Exception) {}
    }
}
