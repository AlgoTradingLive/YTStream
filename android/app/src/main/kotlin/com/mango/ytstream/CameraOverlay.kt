package com.mango.ytstream

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicYuvToRGB
import android.renderscript.Type
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

class CameraOverlay(
    private val context: Context,
    private val onFrame: (Bitmap) -> Unit
) {
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var processThread: HandlerThread? = null
    private var processHandler: Handler? = null
    private val isRunning = AtomicBoolean(false)
    private val isProcessing = AtomicBoolean(false)

    // RenderScript for fast YUV→RGB conversion (GPU accelerated)
    private var rs: RenderScript? = null
    private var yuvToRgb: ScriptIntrinsicYuvToRGB? = null
    private var inputAlloc: Allocation? = null
    private var outputAlloc: Allocation? = null
    private var outputBitmap: Bitmap? = null
    private var imgW = 320
    private var imgH = 240

    companion object {
        private const val TAG = "CameraOverlay"
    }

    private fun initRenderScript(w: Int, h: Int) {
        try {
            rs = RenderScript.create(context)
            yuvToRgb = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs))

            val yuvSize = w * h * 3 / 2  // YUV_420_888 size
            val yuvType = Type.Builder(rs, Element.U8(rs)).setX(yuvSize).create()
            inputAlloc = Allocation.createTyped(rs, yuvType, Allocation.USAGE_SCRIPT)

            val rgbType = Type.Builder(rs, Element.RGBA_8888(rs))
                .setX(w).setY(h).create()
            outputAlloc = Allocation.createTyped(rs, rgbType, Allocation.USAGE_SCRIPT)

            outputBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Log.d(TAG, "RenderScript initialized ${w}x${h}")
        } catch (e: Exception) {
            Log.e(TAG, "RenderScript init failed: ${e.message}")
            rs = null
        }
    }

    private fun yuvToRgbBitmap(image: android.media.Image): Bitmap? {
        return try {
            val yBuffer = image.planes[0].buffer
            val uBuffer = image.planes[1].buffer
            val vBuffer = image.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            if (rs != null && inputAlloc != null && outputAlloc != null && outputBitmap != null) {
                // RenderScript GPU conversion
                inputAlloc!!.copyFrom(nv21)
                yuvToRgb!!.setInput(inputAlloc)
                yuvToRgb!!.forEach(outputAlloc)
                outputAlloc!!.copyTo(outputBitmap)
                outputBitmap!!.copy(Bitmap.Config.ARGB_8888, true)
            } else {
                // Fallback: simple YUV→RGB in software
                yuvNv21ToBitmap(nv21, imgW, imgH)
            }
        } catch (e: Exception) {
            Log.e(TAG, "YUV convert error: ${e.message}")
            null
        }
    }

    private fun yuvNv21ToBitmap(yuv: ByteArray, width: Int, height: Int): Bitmap {
        val android_yuv = android.graphics.YuvImage(
            yuv, ImageFormat.NV21, width, height, null
        )
        val out = java.io.ByteArrayOutputStream()
        android_yuv.compressToJpeg(android.graphics.Rect(0, 0, width, height), 80, out)
        val bytes = out.toByteArray()
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }

    fun start(useFront: Boolean, isPortrait: Boolean = false) {
        if (isRunning.get()) stop()

        imgW = if (isPortrait) 240 else 320
        imgH = if (isPortrait) 320 else 240

        cameraThread = HandlerThread("CameraThread").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
        processThread = HandlerThread("CameraProcessThread").also { it.start() }
        processHandler = Handler(processThread!!.looper)

        initRenderScript(imgW, imgH)

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        var cameraId: String? = null
        try {
            for (id in manager.cameraIdList) {
                val facing = manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING)
                if (useFront && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    cameraId = id; break
                } else if (!useFront && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id; break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Camera list error: ${e.message}"); return
        }

        if (cameraId == null) { Log.e(TAG, "No camera found"); return }

        // YUV_420_888 — JPEG पेक्षा खूप fast, GPU load नाही
        imageReader = ImageReader.newInstance(imgW, imgH, ImageFormat.YUV_420_888, 2)
        imageReader!!.setOnImageAvailableListener({ reader ->
            if (isProcessing.getAndSet(true)) {
                try { reader.acquireLatestImage()?.close() } catch (_: Exception) {}
                return@setOnImageAvailableListener
            }
            val image = try { reader.acquireLatestImage() } catch (_: Exception) { null }
            if (image == null) { isProcessing.set(false); return@setOnImageAvailableListener }

            processHandler?.post {
                try {
                    if (isRunning.get()) {
                        val bitmap = yuvToRgbBitmap(image)
                        image.close()
                        if (bitmap != null) onFrame(bitmap)
                    } else {
                        image.close()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Process error: ${e.message}")
                    try { image.close() } catch (_: Exception) {}
                } finally {
                    isProcessing.set(false)
                }
            }
        }, cameraHandler)

        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    try {
                        val surface = imageReader!!.surface
                        camera.createCaptureSession(listOf(surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    captureSession = session
                                    try {
                                        val req = camera.createCaptureRequest(
                                            CameraDevice.TEMPLATE_PREVIEW
                                        ).apply {
                                            addTarget(surface)
                                            // Low FPS — दुसरं app असताना CPU/GPU कमी वापर
                                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                                android.util.Range(8, 12))
                                        }.build()
                                        session.setRepeatingRequest(req, null, cameraHandler)
                                        isRunning.set(true)
                                        Log.d(TAG, "Camera YUV started OK")
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Request error: ${e.message}")
                                    }
                                }
                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    Log.e(TAG, "Configure failed"); isRunning.set(false)
                                }
                            }, cameraHandler)
                    } catch (e: Exception) {
                        Log.e(TAG, "Session error: ${e.message}")
                    }
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close(); cameraDevice = null; isRunning.set(false)
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    camera.close(); cameraDevice = null; isRunning.set(false)
                }
            }, cameraHandler)
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied")
        } catch (e: Exception) {
            Log.e(TAG, "openCamera error: ${e.message}")
        }
    }

    fun stop() {
        isRunning.set(false)
        isProcessing.set(false)
        try { captureSession?.stopRepeating() } catch (_: Exception) {}
        try { captureSession?.close() } catch (_: Exception) {}
        try { cameraDevice?.close() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { inputAlloc?.destroy() } catch (_: Exception) {}
        try { outputAlloc?.destroy() } catch (_: Exception) {}
        try { outputBitmap?.recycle() } catch (_: Exception) {}
        try { yuvToRgb?.destroy() } catch (_: Exception) {}
        try { rs?.destroy() } catch (_: Exception) {}
        try { cameraThread?.quitSafely() } catch (_: Exception) {}
        try { processThread?.quitSafely() } catch (_: Exception) {}
        cameraDevice = null; captureSession = null; imageReader = null
        inputAlloc = null; outputAlloc = null; outputBitmap = null
        yuvToRgb = null; rs = null
        cameraThread = null; cameraHandler = null
        processThread = null; processHandler = null
        Log.d(TAG, "Camera stopped")
    }

    fun isActive() = isRunning.get()
}
