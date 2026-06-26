package com.mango.ytstream

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface

class CameraOverlay(
    private val context: Context,
    private val onFrame: (Bitmap) -> Unit
) {
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var isRunning = false

    fun start(useFront: Boolean) {
        cameraThread = HandlerThread("CameraThread").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val cameraId = manager.cameraIdList.firstOrNull { id ->
            val facing = manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING)
            if (useFront) facing == CameraCharacteristics.LENS_FACING_FRONT
            else facing == CameraCharacteristics.LENS_FACING_BACK
        } ?: return

        imageReader = ImageReader.newInstance(480, 640, PixelFormat.RGBA_8888, 2)
        imageReader!!.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * 480
                val bitmap = Bitmap.createBitmap(
                    480 + rowPadding / pixelStride, 640, Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)
                onFrame(bitmap)
            } catch (_: Exception) {
            } finally {
                image.close()
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
                                isRunning = true
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) {}
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
        isRunning = false
        try { captureSession?.close() } catch (_: Exception) {}
        try { cameraDevice?.close() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        cameraThread?.quitSafely()
        cameraDevice = null
        captureSession = null
        imageReader = null
        cameraThread = null
        cameraHandler = null
    }

    fun isActive() = isRunning
}
