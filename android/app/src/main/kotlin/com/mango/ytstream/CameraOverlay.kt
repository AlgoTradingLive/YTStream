package com.mango.ytstream

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class CameraOverlay(
    private val context: Context,
    private val onFrame: (Bitmap) -> Unit,
    private val onCameraError: (() -> Unit)? = null  // ← नवीन: freeze/error झाल्यावर StreamService ला सांगण्यासाठी
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
    private var imgW = 320
    private var imgH = 240

    companion object {
        private const val TAG = "CameraOverlay"
    }

    // YUV_420_888 → NV21 → JPEG → Bitmap
    private fun imageToNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val nv21 = ByteArray(width * height * 3 / 2)

        var pos = 0
        for (row in 0 until height) {
            yBuffer.position(row * yRowStride)
            yBuffer.get(nv21, pos, width)
            pos += width
        }

        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                val uvIndex = row * uvRowStride + col * uvPixelStride
                vBuffer.position(uvIndex)
                nv21[pos++] = vBuffer.get()
                uBuffer.position(uvIndex)
                nv21[pos++] = uBuffer.get()
            }
        }

        return nv21
    }

    private fun nv21ToBitmap(nv21: ByteArray, w: Int, h: Int): Bitmap? {
        return try {
            val yuv = YuvImage(nv21, ImageFormat.NV21, w, h, null)
            val out = ByteArrayOutputStream()
            yuv.compressToJpeg(Rect(0, 0, w, h), 75, out)
            val bytes = out.toByteArray()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "nv21ToBitmap error: ${e.message}")
            null
        }
    }

    fun start(useFront: Boolean, isPortrait: Boolean = false) {
        if (isRunning.get()) stop()

        imgW = if (isPortrait) 240 else 320
        imgH = if (isPortrait) 320 else 240

        cameraThread = HandlerThread("CameraThread").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
        processThread = HandlerThread("CameraProcessThread").also { it.start() }
        processHandler = Handler(processThread!!.looper)

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
                        val nv21 = imageToNv21(image)
                        image.close()
                        val bitmap = nv21ToBitmap(nv21, imgW, imgH)
                        if (bitmap != null) {
                            val copy = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                            bitmap.recycle()
                            onFrame(copy)
                        }
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
                                override fun onConfigureFailed(s: CameraCaptureSession) {
                                    Log.e(TAG, "Configure failed")
                                    isRunning.set(false)
                                    // ← Configure fail झाला तर पण callback
                                    onCameraError?.invoke()
                                }
                            }, cameraHandler)
                    } catch (e: Exception) {
                        Log.e(TAG, "Session error: ${e.message}")
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    // ← Single app share मध्ये हेच trigger होतं!
                    Log.w(TAG, "Camera disconnected (likely interrupted by screen share)")
                    camera.close()
                    cameraDevice = null
                    isRunning.set(false)
                    onCameraError?.invoke()  // StreamService ला सांग → restart होईल
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    camera.close()
                    cameraDevice = null
                    isRunning.set(false)
                    onCameraError?.invoke()  // StreamService ला सांग → restart होईल
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
        try { cameraThread?.quitSafely() } catch (_: Exception) {}
        try { processThread?.quitSafely() } catch (_: Exception) {}
        cameraDevice = null; captureSession = null; imageReader = null
        cameraThread = null; cameraHandler = null
        processThread = null; processHandler = null
        Log.d(TAG, "Camera stopped")
    }

    fun isActive() = isRunning.get()
}
