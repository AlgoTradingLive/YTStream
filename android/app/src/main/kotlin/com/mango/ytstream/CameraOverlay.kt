package com.mango.ytstream

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
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

    fun start(useFront: Boolean, isPortrait: Boolean = false) {
        if (isRunning.get()) stop()

        cameraThread = HandlerThread("CameraThread").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)

        processThread = HandlerThread("CameraProcessThread").also { it.start() }
        processHandler = Handler(processThread!!.looper)

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        var cameraId: String? = null
        try {
            for (id in manager.cameraIdList) {
                val characteristics = manager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (useFront && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    cameraId = id; break
                } else if (!useFront && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id; break
                }
            }
        } catch (e: Exception) {
            Log.e("CameraOverlay", "Camera list error: ${e.message}")
            return
        }

        if (cameraId == null) {
            Log.e("CameraOverlay", "No camera found useFront=$useFront")
            return
        }

        val w = if (isPortrait) 240 else 320
        val h = if (isPortrait) 320 else 240

        imageReader = ImageReader.newInstance(w, h, ImageFormat.JPEG, 2)
        imageReader!!.setOnImageAvailableListener({ reader ->
            if (isProcessing.getAndSet(true)) {
                try { reader.acquireLatestImage()?.close() } catch (_: Exception) {}
                return@setOnImageAvailableListener
            }

            val image = try { reader.acquireLatestImage() } catch (_: Exception) { null }
            if (image == null) {
                isProcessing.set(false)
                return@setOnImageAvailableListener
            }

            processHandler?.post {
                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    image.close()

                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null && isRunning.get()) {
                        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                        bitmap.recycle()
                        onFrame(copy)
                    } else {
                        bitmap?.recycle()
                    }
                } catch (e: Exception) {
                    Log.e("CameraOverlay", "Process error: ${e.message}")
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
                    val surface = imageReader!!.surface
                    try {
                        camera.createCaptureSession(
                            listOf(surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    captureSession = session
                                    try {
                                        val request = camera.createCaptureRequest(
                                            CameraDevice.TEMPLATE_PREVIEW
                                        ).apply {
                                            addTarget(surface)
                                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                                android.util.Range(8, 15))
                                        }.build()
                                        session.setRepeatingRequest(request, null, cameraHandler)
                                        isRunning.set(true)
                                        Log.d("CameraOverlay", "Camera started OK")
                                    } catch (e: Exception) {
                                        Log.e("CameraOverlay", "Request error: ${e.message}")
                                        isRunning.set(false)
                                    }
                                }
                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    Log.e("CameraOverlay", "Session configure failed")
                                    isRunning.set(false)
                                }
                            },
                            cameraHandler
                        )
                    } catch (e: Exception) {
                        Log.e("CameraOverlay", "createCaptureSession error: ${e.message}")
                    }
                }
                override fun onDisconnected(camera: CameraDevice) {
                    Log.w("CameraOverlay", "Camera disconnected")
                    camera.close()
                    cameraDevice = null
                    isRunning.set(false)
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e("CameraOverlay", "Camera error: $error")
                    camera.close()
                    cameraDevice = null
                    isRunning.set(false)
                }
            }, cameraHandler)
        } catch (e: SecurityException) {
            Log.e("CameraOverlay", "Camera permission denied: ${e.message}")
        } catch (e: Exception) {
            Log.e("CameraOverlay", "openCamera error: ${e.message}")
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
        cameraDevice = null
        captureSession = null
        imageReader = null
        cameraThread = null
        cameraHandler = null
        processThread = null
        processHandler = null
        Log.d("CameraOverlay", "Camera stopped")
    }

    fun isActive() = isRunning.get()
}
