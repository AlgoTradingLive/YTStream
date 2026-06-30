package com.mango.ytstream

import android.content.Context
import android.graphics.*
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.atomic.AtomicBoolean

/**
 * FaceFilterProcessor
 *
 * ML Kit वापरून चेहरा ओळखतो आणि त्यावर mask overlay करतो.
 * CameraOverlay च्या onFrame मध्ये हे call करायचं.
 *
 * Filters: batman | superman | dog | anonymous | robot | tribal |
 *          webslinger | tiger | holi | galaxy | none
 */
class FaceFilterProcessor(private val context: Context) {

    companion object {
        private const val TAG = "FaceFilterProcessor"
    }

    @Volatile
    private var currentFilter = "none"

    private val detector: FaceDetector
    private val isProcessing = AtomicBoolean(false)

    init {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.15f)
            .enableTracking()
            .build()

        detector = FaceDetection.getClient(options)
        Log.d(TAG, "FaceFilterProcessor initialized")
    }

    fun setFilter(filterName: String) {
        currentFilter = filterName
        Log.d(TAG, "Filter changed to: $filterName")
    }

    fun getCurrentFilter() = currentFilter

    fun process(inputBitmap: Bitmap): Bitmap {
        if (currentFilter == "none") return inputBitmap
        if (!isProcessing.compareAndSet(false, true)) return inputBitmap

        return try {
            val image = InputImage.fromBitmap(inputBitmap, 0)
            val faces = com.google.android.gms.tasks.Tasks.await(
                detector.process(image), 300, java.util.concurrent.TimeUnit.MILLISECONDS
            )

            if (faces.isEmpty()) {
                inputBitmap
            } else {
                val result = inputBitmap.copy(Bitmap.Config.ARGB_8888, true)
                val canvas = Canvas(result)
                for (face in faces) drawMask(canvas, face, result.width, result.height)
                inputBitmap.recycle()
                result
            }
        } catch (e: java.util.concurrent.TimeoutException) {
            Log.w(TAG, "Face detection timeout — skipping frame")
            inputBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Process error: ${e.message}")
            inputBitmap
        } finally {
            isProcessing.set(false)
        }
    }

    private fun drawMask(canvas: Canvas, face: Face, imgW: Int, imgH: Int) {
        val bounds = face.boundingBox
        val safeLeft   = bounds.left.coerceAtLeast(0)
        val safeTop    = bounds.top.coerceAtLeast(0)
        val safeRight  = bounds.right.coerceAtMost(imgW)
        val safeBottom = bounds.bottom.coerceAtMost(imgH)
        if (safeRight <= safeLeft || safeBottom <= safeTop) return

        val faceW = safeRight - safeLeft
        val faceH = safeBottom - safeTop

        when (currentFilter) {
            "batman"     -> drawBatmanMask(canvas, safeLeft, safeTop, faceW, faceH)
            "superman"   -> drawSupermanMask(canvas, safeLeft, safeTop, faceW, faceH)
            "dog"        -> drawDogFilter(canvas, safeLeft, safeTop, faceW, faceH)
            "anonymous"  -> drawAnonymousMask(canvas, safeLeft, safeTop, faceW, faceH)
            "robot"      -> drawRobotMask(canvas, safeLeft, safeTop, faceW, faceH)
            "tribal"     -> drawTribalMask(canvas, safeLeft, safeTop, faceW, faceH)
            "webslinger" -> drawWebslingerMask(canvas, safeLeft, safeTop, faceW, faceH)
            "tiger"      -> drawTigerMask(canvas, safeLeft, safeTop, faceW, faceH)
            "holi"       -> drawHoliFilter(canvas, safeLeft, safeTop, faceW, faceH)
            "galaxy"     -> drawGalaxyMask(canvas, safeLeft, safeTop, faceW, faceH)
        }
    }

    private fun drawBatmanMask(canvas: Canvas, x: Int, y: Int, w: Int, h: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.argb(230, 10, 10, 10)
        val maskPath = Path().apply {
            val cx = x + w / 2f
            moveTo(x.toFloat(), y + h * 0.25f)
            cubicTo(x.toFloat(), y.toFloat(), cx - w * 0.1f, y - h * 0.05f, cx, y - h * 0.05f)
            cubicTo(cx + w * 0.1f, y - h * 0.05f, (x + w).toFloat(), y.toFloat(), (x + w).toFloat(), y + h * 0.25f)
            cubicTo((x + w).toFloat(), y + h * 0.55f, cx + w * 0.35f, y + h * 0.6f, cx, y + h * 0.58f)
            cubicTo(cx - w * 0.35f, y + h * 0.6f, x.toFloat(), y + h * 0.55f, x.toFloat(), y + h * 0.25f)
            close()
        }
        canvas.drawPath(maskPath, paint)
        paint.color = Color.argb(240, 10, 10, 10)
        canvas.drawPath(Path().apply {
            moveTo(x + w * 0.18f, y + h * 0.08f); lineTo(x + w * 0.08f, y - h * 0.28f); lineTo(x + w * 0.35f, y + h * 0.05f); close()
        }, paint)
        canvas.drawPath(Path().apply {
            moveTo(x + w * 0.82f, y + h * 0.08f); lineTo(x + w * 0.92f, y - h * 0.28f); lineTo(x + w * 0.65f, y + h * 0.05f); close()
        }, paint)
        paint.color = Color.argb(180, 240, 240, 240)
        canvas.drawOval(RectF(x + w * 0.12f, y + h * 0.22f, x + w * 0.44f, y + h * 0.40f), paint)
        canvas.drawOval(RectF(x + w * 0.56f, y + h * 0.22f, x + w * 0.88f, y + h * 0.40f), paint)
        paint.color = Color.argb(200, 255, 255, 255)
        canvas.drawOval(RectF(x + w * 0.14f, y + h * 0.24f, x + w * 0.42f, y + h * 0.38f), paint)
        canvas.drawOval(RectF(x + w * 0.58f, y + h * 0.24f, x + w * 0.86f, y + h * 0.38f), paint)
    }

    private fun drawSupermanMask(canvas: Canvas, x: Int, y: Int, w: Int, h: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.argb(210, 180, 0, 0)
        val maskPath = Path().apply {
            val cx = x + w / 2f
            moveTo(x + w * 0.05f, y + h * 0.28f)
            cubicTo(x.toFloat(), y + h * 0.15f, x + w * 0.15f, y + h * 0.12f, x + w * 0.30f, y + h * 0.18f)
            lineTo(x + w * 0.35f, y + h * 0.30f); lineTo(cx - w * 0.06f, y + h * 0.30f); lineTo(cx + w * 0.06f, y + h * 0.30f)
            lineTo(x + w * 0.65f, y + h * 0.30f); lineTo(x + w * 0.70f, y + h * 0.18f)
            cubicTo(x + w * 0.85f, y + h * 0.12f, (x + w).toFloat(), y + h * 0.15f, x + w * 0.95f, y + h * 0.28f)
            cubicTo((x + w).toFloat(), y + h * 0.42f, x + w * 0.80f, y + h * 0.48f, x + w * 0.65f, y + h * 0.44f)
            lineTo(cx + w * 0.06f, y + h * 0.44f); lineTo(cx - w * 0.06f, y + h * 0.44f); lineTo(x + w * 0.35f, y + h * 0.44f)
            cubicTo(x + w * 0.20f, y + h * 0.48f, x.toFloat(), y + h * 0.42f, x + w * 0.05f, y + h * 0.28f)
            close()
        }
        canvas.drawPath(maskPath, paint)
        paint.color = Color.argb(200, 220, 180, 140)
        canvas.drawOval(RectF(x + w * 0.10f, y + h * 0.18f, x + w * 0.40f, y + h * 0.40f), paint)
        canvas.drawOval(RectF(x + w * 0.60f, y + h * 0.18f, x + w * 0.90f, y + h * 0.40f), paint)
        val shieldPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        shieldPaint.color = Color.argb(220, 180, 0, 0)
        canvas.drawRoundRect(RectF(x + w * 0.40f, y + h * 0.02f, x + w * 0.60f, y + h * 0.16f), 8f, 8f, shieldPaint)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(255, 255, 210, 0); textSize = w * 0.12f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }
        canvas.drawText("S", x + w * 0.50f, y + h * 0.14f, textPaint)
    }

    private fun drawDogFilter(canvas: Canvas, x: Int, y: Int, w: Int, h: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.argb(220, 180, 100, 40)
        canvas.drawPath(Path().apply {
            moveTo(x + w * 0.05f, y + h * 0.12f)
            cubicTo(x - w * 0.20f, y - h * 0.15f, x - w * 0.05f, y - h * 0.35f, x + w * 0.15f, y - h * 0.10f)
            cubicTo(x + w * 0.08f, y + h * 0.05f, x + w * 0.05f, y + h * 0.10f, x + w * 0.05f, y + h * 0.12f); close()
        }, paint)
        canvas.drawPath(Path().apply {
            moveTo(x + w * 0.95f, y + h * 0.12f)
            cubicTo(x + w * 1.20f, y - h * 0.15f, x + w * 1.05f, y - h * 0.35f, x + w * 0.85f, y - h * 0.10f)
            cubicTo(x + w * 0.92f, y + h * 0.05f, x + w * 0.95f, y + h * 0.10f, x + w * 0.95f, y + h * 0.12f); close()
        }, paint)
        paint.color = Color.argb(230, 30, 20, 10)
        canvas.drawOval(RectF(x + w * 0.35f, y + h * 0.58f, x + w * 0.65f, y + h * 0.74f), paint)
        paint.color = Color.argb(80, 200, 120, 60)
        canvas.drawCircle(x + w * 0.18f, y + h * 0.70f, w * 0.14f, paint)
        canvas.drawCircle(x + w * 0.82f, y + h * 0.70f, w * 0.14f, paint)
        paint.color = Color.argb(120, 120, 60, 20)
        val fr = w * 0.025f
        canvas.drawCircle(x + w * 0.12f, y + h * 0.66f, fr, paint); canvas.drawCircle(x + w * 0.20f, y + h * 0.72f, fr, paint)
        canvas.drawCircle(x + w * 0.14f, y + h * 0.78f, fr, paint); canvas.drawCircle(x + w * 0.88f, y + h * 0.66f, fr, paint)
        canvas.drawCircle(x + w * 0.80f, y + h * 0.72f, fr, paint); canvas.drawCircle(x + w * 0.86f, y + h * 0.78f, fr, paint)
        paint.color = Color.argb(210, 220, 80, 100)
        canvas.drawPath(Path().apply {
            moveTo(x + w * 0.35f, y + h * 0.82f)
            cubicTo(x + w * 0.30f, y + h * 0.95f, x + w * 0.42f, y + h * 1.05f, x + w * 0.50f, y + h * 1.08f)
            cubicTo(x + w * 0.58f, y + h * 1.05f, x + w * 0.70f, y + h * 0.95f, x + w * 0.65f, y + h * 0.82f); close()
        }, paint)
    }

    private fun drawAnonymousMask(canvas: Canvas, x: Int, y: Int, w: Int, h: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.argb(255, 237, 230, 216)
        val faceMask = Path().apply {
            val cx = x + w / 2f
            moveTo(x - w * 0.05f, y + h * 0.05f)
            cubicTo(x - w * 0.08f, y - h * 0.10f, cx, y - h * 0.15f, x + w * 1.05f, y + h * 0.05f)
            lineTo(x + w * 1.05f, y + h * 1.05f)
            cubicTo(x + w * 0.75f, y + h * 1.20f, cx, y + h * 1.15f, x - w * 0.05f, y + h * 1.05f); close()
        }
        canvas.drawPath(faceMask, paint)
        paint.color = Color.argb(255, 40, 40, 40)
        canvas.drawRoundRect(RectF(x + w * 0.10f, y + h * 0.30f, x + w * 0.42f, y + h * 0.38f), 6f, 6f, paint)
        canvas.drawRoundRect(RectF(x + w * 0.58f, y + h * 0.30f, x + w * 0.90f, y + h * 0.38f), 6f, 6f, paint)
        paint.color = Color.argb(140, 184, 172, 146)
        canvas.drawLine(x + w * 0.46f, y + h * 0.42f, x + w * 0.44f, y + h * 0.62f, paint)
        paint.color = Color.argb(180, 184, 172, 146)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f
        canvas.drawPath(Path().apply {
            moveTo(x + w * 0.28f, y + h * 0.78f); quadTo(x + w * 0.50f, y + h * 0.88f, x + w * 0.72f, y + h * 0.78f)
        }, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawRobotMask(canvas: Canvas, x: Int, y: Int, w: Int, h: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.argb(255, 154, 163, 171)
        val faceMask = Path().apply {
            moveTo(x - w * 0.03f, y + h * 0.08f)
            cubicTo(x.toFloat(), y - h * 0.10f, x + w * 0.5f, y - h * 0.18f, x + w * 1.03f, y + h * 0.08f)
            cubicTo(x + w * 1.10f, y + h * 0.45f, x + w * 0.95f, y + h * 0.85f, x + w * 0.5f, y + h * 1.10f)
            cubicTo(x + w * 0.05f, y + h * 0.85f, x - w * 0.10f, y + h * 0.45f, x - w * 0.03f, y + h * 0.08f); close()
        }
        canvas.drawPath(faceMask, paint)
        paint.color = Color.argb(255, 26, 31, 36)
        canvas.drawRoundRect(RectF(x + w * 0.10f, y + h * 0.28f, x + w * 0.44f, y + h * 0.50f), 6f, 6f, paint)
        canvas.drawRoundRect(RectF(x + w * 0.56f, y + h * 0.28f, x + w * 0.90f, y + h * 0.50f), 6f, 6f, paint)
        paint.color = Color.argb(255, 62, 198, 255)
        canvas.drawCircle(x + w * 0.27f, y + h * 0.39f, w * 0.07f, paint)
        canvas.drawCircle(x + w * 0.73f, y + h * 0.39f, w * 0.07f, paint)
        paint.color = Color.argb(255, 26, 31, 36)
        canvas.drawRoundRect(RectF(x + w * 0.30f, y + h * 0.74f, x + w * 0.70f, y + h * 0.84f), 5f, 5f, paint)
        paint.color = Color.argb(255, 62, 198, 255)
        for (i in 0..2) {
            val seg = x + w * (0.34f + i * 0.12f)
            canvas.drawRect(seg, y + h * 0.77f, seg + w * 0.07f, y + h * 0.81f, paint)
        }
        paint.color = Color.argb(180, 93, 102, 110)
        canvas.drawLine(x + w * 0.10f, y + h * 0.18f, x + w * 0.90f, y + h * 0.18f, paint)
        canvas.drawLine(x + w * 0.05f, y + h * 0.58f, x + w * 0.95f, y + h * 0.58f, paint)
        paint.color = Color.argb(255, 62, 198, 255)
        canvas.drawCircle(x + w * 0.5f, y + h * 0.0f, w * 0.04f, paint)
    }

    private fun drawTribalMask(canvas: Canvas, x: Int, y: Int, w: Int, h: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.argb(235, 26, 26, 46)
        val faceMask = Path().apply {
            moveTo(x + w * 0.05f, y + h * 0.10f)
            cubicTo(x + w * 0.10f, y - h * 0.05f, x + w * 0.5f, y - h * 0.12f, x + w * 0.95f, y + h * 0.10f)
            cubicTo(x + w * 1.02f, y + h * 0.45f, x + w * 0.92f, y + h * 0.82f, x + w * 0.5f, y + h * 1.08f)
            cubicTo(x + w * 0.08f, y + h * 0.82f, x - w * 0.02f, y + h * 0.45f, x + w * 0.05f, y + h * 0.10f); close()
        }
        canvas.drawPath(faceMask, paint)
        paint.color = Color.argb(255, 0, 229, 212)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = w * 0.012f
        canvas.drawPath(faceMask, paint)
        canvas.drawOval(RectF(x + w * 0.12f, y + h * 0.32f, x + w * 0.42f, y + h * 0.48f), paint)
        canvas.drawOval(RectF(x + w * 0.58f, y + h * 0.32f, x + w * 0.88f, y + h * 0.48f), paint)
        paint.color = Color.argb(255, 255, 45, 117)
        canvas.drawLine(x + w * 0.5f, y + h * 0.48f, x + w * 0.5f, y + h * 0.62f, paint)
        paint.color = Color.argb(255, 0, 229, 212)
        canvas.drawLine(x + w * 0.20f, y + h * 0.12f, x + w * 0.28f, y + h * 0.06f, paint)
        canvas.drawLine(x + w * 0.28f, y + h * 0.12f, x + w * 0.36f, y + h * 0.06f, paint)
        canvas.drawLine(x + w * 0.64f, y + h * 0.12f, x + w * 0.72f, y + h * 0.06f, paint)
        canvas.drawLine(x + w * 0.72f, y + h * 0.12f, x + w * 0.80f, y + h * 0.06f, paint)
        paint.color = Color.argb(255, 255, 45, 117)
        canvas.drawPath(Path().apply {
            moveTo(x + w * 0.32f, y + h * 0.78f); quadTo(x + w * 0.5f, y + h * 0.86f, x + w * 0.68f, y + h * 0.78f)
        }, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(255, 0, 229, 212)
        canvas.drawCircle(x + w * 0.27f, y + h * 0.40f, w * 0.012f, paint)
        canvas.drawCircle(x + w * 0.73f, y + h * 0.40f, w * 0.012f, paint)
    }

    private fun drawWebslingerMask(canvas: Canvas, x: Int, y: Int, w: Int, h: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.argb(255, 200, 16, 46)
        val faceMask = Path().apply {
            moveTo(x + w * 0.05f, y + h * 0.08f)
            cubicTo(x + w * 0.10f, y - h * 0.08f, x + w * 0.5f, y - h * 0.18f, x + w * 0.95f, y + h * 0.08f)
            cubicTo(x + w * 1.04f, y + h * 0.45f, x + w * 0.92f, y + h * 0.85f, x + w * 0.5f, y + h * 1.10f)
            cubicTo(x + w * 0.08f, y + h * 0.85f, x - w * 0.04f, y + h * 0.45f, x + w * 0.05f, y + h * 0.08f); close()
        }
        canvas.drawPath(faceMask, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = w * 0.01f
        paint.color = Color.argb(255, 10, 31, 110)
        canvas.drawPath(faceMask, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(255, 254, 254, 254)
        canvas.drawOval(RectF(x + w * 0.10f, y + h * 0.30f, x + w * 0.42f, y + h * 0.55f), paint)
        canvas.drawOval(RectF(x + w * 0.58f, y + h * 0.30f, x + w * 0.90f, y + h * 0.55f), paint)
        paint.color = Color.argb(255, 10, 31, 110)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = w * 0.006f
        canvas.drawOval(RectF(x + w * 0.10f, y + h * 0.30f, x + w * 0.42f, y + h * 0.55f), paint)
        canvas.drawOval(RectF(x + w * 0.58f, y + h * 0.30f, x + w * 0.90f, y + h * 0.55f), paint)
        for (cx in listOf(x + w * 0.26f, x + w * 0.74f)) {
            val cyV = y + h * 0.42f
            canvas.drawLine(cx, cyV - h * 0.08f, cx, cyV + h * 0.08f, paint)
            canvas.drawLine(cx - w * 0.13f, cyV, cx + w * 0.13f, cyV, paint)
            canvas.drawLine(cx - w * 0.09f, cyV - h * 0.06f, cx + w * 0.09f, cyV + h * 0.06f, paint)
            canvas.drawLine(cx + w * 0.09f, cyV - h * 0.06f, cx - w * 0.09f, cyV + h * 0.06f, paint)
        }
        paint.color = Color.argb(180, 10, 31, 110)
        canvas.drawLine(x + w * 0.5f, y - h * 0.10f, x + w * 0.5f, y + h * 1.05f, paint)
        canvas.drawLine(x.toFloat(), y + h * 0.05f, x + w * 0.30f, y + h * 0.30f, paint)
        canvas.drawLine((x + w).toFloat(), y + h * 0.05f, x + w * 0.70f, y + h * 0.30f, paint)
        canvas.drawLine(x.toFloat(), y + h * 0.65f, x + w * 0.30f, y + h * 0.78f, paint)
        canvas.drawLine((x + w).toFloat(), y + h * 0.65f, x + w * 0.70f, y + h * 0.78f, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawTigerMask(canvas: Canvas, x: Int, y: Int, w: Int, h: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.argb(255, 26, 26, 26)
        canvas.drawPath(Path().apply { moveTo(x + w * 0.27f, y + h * 0.36f); lineTo(x + w * 0.20f, y + h * 0.30f); lineTo(x + w * 0.32f, y + h * 0.28f); close() }, paint)
        canvas.drawPath(Path().apply { moveTo(x + w * 0.73f, y + h * 0.36f); lineTo(x + w * 0.80f, y + h * 0.30f); lineTo(x + w * 0.68f, y + h * 0.28f); close() }, paint)
        canvas.drawPath(Path().apply { moveTo(x + w * 0.41f, y + h * 0.34f); lineTo(x + w * 0.37f, y + h * 0.28f); lineTo(x + w * 0.46f, y + h * 0.26f); close() }, paint)
        canvas.drawPath(Path().apply { moveTo(x + w * 0.59f, y + h * 0.34f); lineTo(x + w * 0.63f, y + h * 0.28f); lineTo(x + w * 0.54f, y + h * 0.26f); close() }, paint)
        canvas.drawPath(Path().apply { moveTo(x + w * 0.21f, y + h * 0.60f); lineTo(x + w * 0.13f, y + h * 0.58f); lineTo(x + w * 0.24f, y + h * 0.52f); close() }, paint)
        canvas.drawPath(Path().apply { moveTo(x + w * 0.79f, y + h * 0.60f); lineTo(x + w * 0.87f, y + h * 0.58f); lineTo(x + w * 0.76f, y + h * 0.52f); close() }, paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(255, 255, 0, 0)
        canvas.drawOval(RectF(x + w * 0.12f, y + h * 0.32f, x + w * 0.42f, y + h * 0.49f), paint)
        canvas.drawOval(RectF(x + w * 0.58f, y + h * 0.32f, x + w * 0.88f, y + h * 0.49f), paint)
        paint.color = Color.argb(140, 255, 80, 80)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = w * 0.025f
        canvas.drawOval(RectF(x + w * 0.12f, y + h * 0.32f, x + w * 0.42f, y + h * 0.49f), paint)
        canvas.drawOval(RectF(x + w * 0.58f, y + h * 0.32f, x + w * 0.88f, y + h * 0.49f), paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(255, 50, 0, 0)
        canvas.drawCircle(x + w * 0.27f, y + h * 0.405f, w * 0.025f, paint)
        canvas.drawCircle(x + w * 0.73f, y + h * 0.405f, w * 0.025f, paint)
        paint.color = Color.argb(255, 26, 26, 26)
        canvas.drawPath(Path().apply {
            moveTo(x + w * 0.5f, y + h * 0.55f); lineTo(x + w * 0.45f, y + h * 0.61f)
            quadTo(x + w * 0.5f, y + h * 0.65f, x + w * 0.55f, y + h * 0.61f); close()
        }, paint)
        paint.color = Color.argb(220, 255, 255, 255)
        paint.strokeWidth = w * 0.008f
        canvas.drawLine(x + w * 0.30f, y + h * 0.65f, x - w * 0.05f, y + h * 0.62f, paint)
        canvas.drawLine(x + w * 0.30f, y + h * 0.68f, x - w * 0.05f, y + h * 0.70f, paint)
        canvas.drawLine(x + w * 0.70f, y + h * 0.65f, x + w * 1.05f, y + h * 0.62f, paint)
        canvas.drawLine(x + w * 0.70f, y + h * 0.68f, x + w * 1.05f, y + h * 0.70f, paint)
    }

    private fun drawHoliFilter(canvas: Canvas, x: Int, y: Int, w: Int, h: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val colors = listOf(
            Color.argb(170, 233, 30, 99), Color.argb(170, 33, 150, 243), Color.argb(170, 255, 235, 59),
            Color.argb(170, 76, 175, 80), Color.argb(170, 255, 152, 0), Color.argb(170, 156, 39, 176),
            Color.argb(170, 0, 188, 212)
        )
        val spots = listOf(
            Triple(0.20f, 0.30f, 0.06f), Triple(0.78f, 0.32f, 0.07f), Triple(0.40f, 0.18f, 0.045f),
            Triple(0.62f, 0.55f, 0.055f), Triple(0.28f, 0.62f, 0.05f), Triple(0.70f, 0.18f, 0.04f),
            Triple(0.50f, 0.70f, 0.05f), Triple(0.15f, 0.50f, 0.035f), Triple(0.85f, 0.55f, 0.04f),
            Triple(0.45f, 0.42f, 0.03f),
        )
        for ((i, spot) in spots.withIndex()) {
            paint.color = colors[i % colors.size]
            canvas.drawCircle(x + w * spot.first, y + h * spot.second, w * spot.third, paint)
        }
    }

    private fun drawGalaxyMask(canvas: Canvas, x: Int, y: Int, w: Int, h: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.argb(225, 26, 11, 61)
        val faceMask = Path().apply {
            moveTo(x + w * 0.05f, y + h * 0.08f)
            cubicTo(x + w * 0.10f, y - h * 0.08f, x + w * 0.5f, y - h * 0.16f, x + w * 0.95f, y + h * 0.08f)
            cubicTo(x + w * 1.03f, y + h * 0.45f, x + w * 0.92f, y + h * 0.85f, x + w * 0.5f, y + h * 1.10f)
            cubicTo(x + w * 0.08f, y + h * 0.85f, x - w * 0.03f, y + h * 0.45f, x + w * 0.05f, y + h * 0.08f); close()
        }
        canvas.drawPath(faceMask, paint)
        paint.color = Color.argb(255, 255, 255, 255)
        val stars = listOf(0.20f to 0.15f, 0.30f to 0.05f, 0.75f to 0.10f, 0.85f to 0.25f, 0.10f to 0.45f,
            0.90f to 0.50f, 0.45f to 0.0f, 0.15f to 0.75f, 0.80f to 0.80f, 0.55f to 0.90f)
        for ((sx, sy) in stars) canvas.drawCircle(x + w * sx, y + h * sy, w * 0.008f, paint)

        paint.color = Color.argb(230, 124, 77, 255)
        canvas.drawOval(RectF(x + w * 0.11f, y + h * 0.32f, x + w * 0.41f, y + h * 0.50f), paint)
        canvas.drawOval(RectF(x + w * 0.59f, y + h * 0.32f, x + w * 0.89f, y + h * 0.50f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = w * 0.01f
        paint.color = Color.argb(255, 179, 157, 219)
        canvas.drawOval(RectF(x + w * 0.11f, y + h * 0.32f, x + w * 0.41f, y + h * 0.50f), paint)
        canvas.drawOval(RectF(x + w * 0.59f, y + h * 0.32f, x + w * 0.89f, y + h * 0.50f), paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(255, 225, 190, 231)
        canvas.drawCircle(x + w * 0.26f, y + h * 0.41f, w * 0.025f, paint)
        canvas.drawCircle(x + w * 0.74f, y + h * 0.41f, w * 0.025f, paint)
        paint.color = Color.argb(160, 124, 77, 255)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = w * 0.006f
        canvas.drawLine(x + w * 0.20f, y + h * 0.65f, x + w * 0.80f, y + h * 0.65f, paint)
        canvas.drawLine(x + w * 0.25f, y - h * 0.02f, x + w * 0.75f, y - h * 0.02f, paint)
        paint.style = Paint.Style.FILL
    }

    fun release() {
        try {
            detector.close()
            Log.d(TAG, "FaceFilterProcessor released")
        } catch (e: Exception) {
            Log.e(TAG, "Release error: ${e.message}")
        }
    }
}
