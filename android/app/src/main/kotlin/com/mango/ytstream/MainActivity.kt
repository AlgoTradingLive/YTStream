package com.mango.ytstream

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private val CHANNEL = "com.mango.ytstream/stream"
    private val REQUEST_MEDIA_PROJECTION = 1001

    private var methodChannel: MethodChannel? = null
    private var pendingResult: MethodChannel.Result? = null
    private var pendingRtmpUrl: String? = null
    private var pendingStreamKey: String? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
        methodChannel!!.setMethodCallHandler { call, result ->
            when (call.method) {
                "startStream" -> {
                    val rtmpUrl = call.argument<String>("rtmpUrl") ?: ""
                    val streamKey = call.argument<String>("streamKey") ?: ""
                    pendingRtmpUrl = rtmpUrl
                    pendingStreamKey = streamKey
                    pendingResult = result
                    requestMediaProjection()
                }
                "stopStream" -> {
                    stopStreamService()
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun requestMediaProjection() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val intent = Intent(this, StreamService::class.java).apply {
                    putExtra("resultCode", resultCode)
                    putExtra("data", data)
                    putExtra("rtmpUrl", pendingRtmpUrl)
                    putExtra("streamKey", pendingStreamKey)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                pendingResult?.success(null)
            } else {
                pendingResult?.error("CANCELLED", "Permission denied", null)
                methodChannel?.invokeMethod("onStreamError", "Permission denied")
            }
            pendingResult = null
        }
    }

    private fun stopStreamService() {
        val intent = Intent(this, StreamService::class.java).apply { action = "STOP" }
        startService(intent)
    }

    fun notifyFlutter(method: String, args: Any? = null) {
        runOnUiThread { methodChannel?.invokeMethod(method, args) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StreamService.mainActivity = this
    }

    override fun onDestroy() {
        super.onDestroy()
        StreamService.mainActivity = null
    }
}
