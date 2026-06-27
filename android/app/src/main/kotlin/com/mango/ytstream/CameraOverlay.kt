package com.mango.ytstream

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

class CameraOverlay(
    private val context: Context,
    private val onSurfaceTexture: (SurfaceTexture) -> Unit,
    private val onStopped: () -> Unit = {}
) {
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private val isRunning = AtomicBoolean(false)

    companion object {
        private const val TAG = "CameraOverlay"
        // Camera preview size — GL filter साठी
        const val PREVIEW_W = 320
        const val PREVIEW_H = 240
    }

    fun start(useFront: Boolean, isPortrait: Boolean = false) {
        if (isRunning.get()) stop()

        cameraThread = HandlerThread("CameraThread").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)

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
            Log.e(TAG, "Camera list error: ${e.message}")
            return
        }

        if (cameraId == null) {
            Log.e(TAG, "No camera found")
            return
        }

        // SurfaceTexture बनवा — GL texture ID 0 (external)
        val st = SurfaceTexture(0)
        val w = if (isPortrait) PREVIEW_H else PREVIEW_W
        val h = if (isPortrait) PREVIEW_W else PREVIEW_H
        st.setDefaultBufferSize(w, h)
        surfaceTexture = st

        // GL filter ला SurfaceTexture द्या
        onSurfaceTexture(st)

        val sf = Surface(st)
        surface = sf

        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    try {
                        camera.createCaptureSession(
                            listOf(sf),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    captureSession = session
                                    try {
                                        val req = camera.createCaptureRequest(
                                            CameraDevice.TEMPLATE_PREVIEW
                                        ).apply {
                                            addTarget(sf)
                                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                                android.util.Range(15, 30))
                                        }.build()
                                        session.setRepeatingRequest(req, null, cameraHandler)
                                        isRunning.set(true)
                                        Log.d(TAG, "Camera started OK")
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Request error: ${e.message}")
                                        isRunning.set(false)
                                    }
                                }
                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    Log.e(TAG, "Session configure failed")
                                    isRunning.set(false)
                                }
                            }, cameraHandler
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "createCaptureSession error: ${e.message}")
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera disconnected")
                    camera.close(); cameraDevice = null; isRunning.set(false)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    camera.close(); cameraDevice = null; isRunning.set(false)
                }
            }, cameraHandler)
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "openCamera error: ${e.message}")
        }
    }

    fun stop() {
        isRunning.set(false)
        try { captureSession?.stopRepeating() } catch (_: Exception) {}
        try { captureSession?.close() } catch (_: Exception) {}
        try { cameraDevice?.close() } catch (_: Exception) {}
        try { surface?.release() } catch (_: Exception) {}
        try { surfaceTexture?.release() } catch (_: Exception) {}
        try { cameraThread?.quitSafely() } catch (_: Exception) {}
        cameraDevice = null
        captureSession = null
        surface = null
        surfaceTexture = null
        cameraThread = null
        cameraHandler = null
        onStopped()
        Log.d(TAG, "Camera stopped")
    }

    fun isActive() = isRunning.get()
}
