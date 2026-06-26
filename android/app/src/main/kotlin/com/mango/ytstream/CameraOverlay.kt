package com.mango.ytstream

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
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

    fun start(useFront: Boolean) {
        if (isRunning.get()) stop()

        cameraThread = HandlerThread("CameraThread").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)

        // ✅ वेगळा thread — JPEG decode साठी
        processThread = HandlerThread("CameraProcessThread").also { it.start() }
        processHandler = Handler(processThread!!.looper)

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        var cameraId: String? = null
        for (id in manager.cameraIdList) {
            val characteristics = manager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (useFront && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                cameraId = id; break
            } else if (!useFront && facing == CameraCharacteristics.LENS_FACING_BACK) {
                cameraId = id; break
            }
        }
        if (cameraId == null) return

        // ✅ फक्त 1 buffer — lag कमी होईल
        imageReader = ImageReader.newInstance(320, 240, ImageFormat.JPEG, 1)
        imageReader!!.setOnImageAvailableListener({ reader ->
            // ✅ आधीचं processing चालू असेल तर skip कर
            if (isProcessing.getAndSet(true)) {
                reader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }

            val image = reader.acquireLatestImage()
            if (image == null) {
                isProcessing.set(false)
                return@setOnImageAvailableListener
            }

            // ✅ Process thread वर decode करतो — camera thread block होणार नाही
            processHandler?.post {
                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    image.close()

                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null && isRunning.get()) {
                        onFrame(bitmap)
                    }
                } catch (_: Exception) {
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
                    camera.createCaptureSession(
                        listOf(surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                captureSession = session
                                val request = camera.createCaptureRequest(
                                    CameraDevice.TEMPLATE_PREVIEW
                                ).apply {
                                    addTarget(surface)
                                }.build()
                                session.setRepeatingRequest(request, null, cameraHandler)
                                isRunning.set(true)
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                isRunning.set(false)
                            }
                        },
                        cameraHandler
                    )
                }
                override fun onDisconnected(camera: CameraDevice) { stop() }
                override fun onError(camera: CameraDevice, error: Int) { stop() }
            }, cameraHandler)
        } catch (_: SecurityException) {}
    }

    fun stop() {
        isRunning.set(false)
        try { captureSession?.close() } catch (_: Exception) {}
        try { cameraDevice?.close() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        cameraThread?.quitSafely()
        processThread?.quitSafely()
        cameraDevice = null
        captureSession = null
        imageReader = null
        cameraThread = null
        cameraHandler = null
        processThread = null
        processHandler = null
    }

    fun isActive() = isRunning.get()
}
