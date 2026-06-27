package com.mango.ytstream

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private val CHANNEL = "com.mango.ytstream/stream"
    private val REQUEST_MEDIA_PROJECTION = 1001
    private val REQUEST_OVERLAY = 1002
    private val REQUEST_MIC_PERMISSION = 1003

    private var methodChannel: MethodChannel? = null
    private var pendingResult: MethodChannel.Result? = null
    private var pendingRtmpUrl: String? = null
    private var pendingStreamKey: String? = null
    private var pendingAudioMode: String? = null
    private var pendingOrientation: String? = null
    private var pendingOverlayText: String? = null
    private var pendingOverlayImagePath: String? = null
    private var pendingTextX: Double = 0.05
    private var pendingTextY: Double = 0.05
    private var pendingImageX: Double = 0.7
    private var pendingImageY: Double = 0.05
    private var pendingCameraEnabled: Boolean = false
    private var pendingCameraFacing: String = "back"
    private var pendingCameraMode: String = "pip"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
        methodChannel!!.setMethodCallHandler { call, result ->
            when (call.method) {
                "startStream" -> {
                    pendingRtmpUrl = call.argument("rtmpUrl")
                    pendingStreamKey = call.argument("streamKey")
                    pendingAudioMode = call.argument("audioMode") ?: "internal"
                    pendingOrientation = call.argument("orientation") ?: "landscape"
                    pendingOverlayText = call.argument("overlayText") ?: ""
                    pendingOverlayImagePath = call.argument("overlayImagePath") ?: ""
                    pendingTextX = call.argument("textX") ?: 0.05
                    pendingTextY = call.argument("textY") ?: 0.05
                    pendingImageX = call.argument("imageX") ?: 0.7
                    pendingImageY = call.argument("imageY") ?: 0.05
                    pendingCameraEnabled = call.argument("cameraEnabled") ?: false
                    pendingCameraFacing = call.argument("cameraFacing") ?: "back"
                    pendingCameraMode = call.argument("cameraMode") ?: "pip"
                    pendingResult = result
                    checkPermissionsAndStart()
                }
                "updateOverlay" -> {
                    val i = Intent(this, StreamService::class.java).apply {
                        action = "UPDATE_OVERLAY"
                        putExtra("overlayText", call.argument("overlayText") ?: "")
                        putExtra("overlayImagePath", call.argument("overlayImagePath") ?: "")
                        putExtra("textX", (call.argument("textX") as? Double)?.toFloat() ?: 0.05f)
                        putExtra("textY", (call.argument("textY") as? Double)?.toFloat() ?: 0.05f)
                        putExtra("imageX", (call.argument("imageX") as? Double)?.toFloat() ?: 0.7f)
                        putExtra("imageY", (call.argument("imageY") as? Double)?.toFloat() ?: 0.05f)
                    }
                    startService(i)
                    result.success(null)
                }
                "setVoiceMode" -> {
                    val mode = call.argument<String>("voiceMode") ?: "normal"
                    val i = Intent(this, StreamService::class.java).apply {
                        action = "SET_VOICE"
                        putExtra("voiceMode", mode)
                    }
                    startService(i)
                    result.success(null)
                }
                "stopStream" -> {
                    stopStreamService()
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun checkPermissionsAndStart() {
        val perms = mutableListOf<String>()

        if (pendingAudioMode == "mic_internal") {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                perms.add(android.Manifest.permission.RECORD_AUDIO)
            }
        }

        if (pendingCameraEnabled) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
                perms.add(android.Manifest.permission.CAMERA)
            }
        }

        if (perms.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), REQUEST_MIC_PERMISSION)
            return
        }
        checkOverlayAndStart()
    }

    private fun checkOverlayAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                REQUEST_OVERLAY
            )
        } else {
            requestMediaProjection()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                checkOverlayAndStart()
            } else {
                pendingResult?.error("MIC_DENIED", "Permission denied", null)
                methodChannel?.invokeMethod("onStreamError", "Permission denied")
                pendingResult = null
            }
        }
    }

    private fun requestMediaProjection() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_OVERLAY -> requestMediaProjection()
            REQUEST_MEDIA_PROJECTION -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    // Screen actual size मिळव — portrait/landscape साठी correct resolution
                    val metrics = resources.displayMetrics
                    val screenW = metrics.widthPixels
                    val screenH = metrics.heightPixels

                    val intent = Intent(this, StreamService::class.java).apply {
                        putExtra("resultCode", resultCode)
                        putExtra("data", data)
                        putExtra("rtmpUrl", pendingRtmpUrl)
                        putExtra("streamKey", pendingStreamKey)
                        putExtra("audioMode", pendingAudioMode)
                        putExtra("orientation", pendingOrientation)
                        putExtra("screenWidth", screenW)   // ← actual screen size
                        putExtra("screenHeight", screenH)  // ← actual screen size
                        putExtra("overlayText", pendingOverlayText ?: "")
                        putExtra("overlayImagePath", pendingOverlayImagePath ?: "")
                        putExtra("textX", pendingTextX.toFloat())
                        putExtra("textY", pendingTextY.toFloat())
                        putExtra("imageX", pendingImageX.toFloat())
                        putExtra("imageY", pendingImageY.toFloat())
                        putExtra("cameraEnabled", pendingCameraEnabled)
                        putExtra("cameraFacing", pendingCameraFacing)
                        putExtra("cameraMode", pendingCameraMode)
                        putExtra("singleAppShare", false)  // ← missing होतं, add केलं
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
                    else startService(intent)
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this))
                        startService(Intent(this, FloatingButtonService::class.java))
                    pendingResult?.success(null)
                } else {
                    pendingResult?.error("CANCELLED", "Permission denied", null)
                    methodChannel?.invokeMethod("onStreamError", "Permission denied")
                }
                pendingResult = null
            }
        }
    }

    private fun stopStreamService() {
        startService(Intent(this, StreamService::class.java).apply { action = "STOP" })
        stopService(Intent(this, FloatingButtonService::class.java))
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
