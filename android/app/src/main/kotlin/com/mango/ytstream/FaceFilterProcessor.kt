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
 * वापर:
 *   val filter = FaceFilterProcessor(context)
 *   filter.setFilter("batman")   // "batman" | "superman" | "dog" | "none"
 *   val resultBitmap = filter.process(inputBitmap)
 */
class FaceFilterProcessor(private val context: Context) {

    companion object {
        private const val TAG = "FaceFilterProcessor"
    }

    // ── सध्याचा filter कोणता आहे ──────────────────────────────────────────
    @Volatile
    private var currentFilter = "none"

    // ── ML Kit detector ────────────────────────────────────────────────────
    private val detector: FaceDetector

    // ── Processing lock — एकावेळी एकच frame process व्हायला हवा ──────────
    private val isProcessing = AtomicBoolean(false)

    // ── Cached Paint objects (object allocation टाळण्यासाठी) ───────────────
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
    }
    private val debugPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    init {
        // FAST mode — real-time साठी
        // Contour detection OFF — PiP/overlay साठी लागत नाही, speed वाढतो
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)  // डोळे, नाक, कान साठी
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.15f)   // frame च्या किमान 15% मोठा चेहरा detect करतो
            .enableTracking()        // video frames मध्ये चेहरा track करतो
            .build()

        detector = FaceDetection.getClient(options)
        Log.d(TAG, "FaceFilterProcessor initialized")
    }

    // ── Filter set करा ────────────────────────────────────────────────────
    fun setFilter(filterName: String) {
        currentFilter = filterName
        Log.d(TAG, "Filter changed to: $filterName")
    }

    fun getCurrentFilter() = currentFilter

    // ── Main process function ──────────────────────────────────────────────
    // inputBitmap घेतो, faces detect करतो, mask overlay करतो, result देतो
    // हे SYNCHRONOUS आहे — CameraOverlay च्या processHandler thread वर call करा
    fun process(inputBitmap: Bitmap): Bitmap {

        // "none" असेल तर directly return
        if (currentFilter == "none") return inputBitmap

        // already processing असेल तर original return — frame drop करण्यापेक्षा बरं
        if (!isProcessing.compareAndSet(false, true)) return inputBitmap

        return try {
            // ML Kit InputImage बनव
            val image = InputImage.fromBitmap(inputBitmap, 0)

            // Synchronous detection — Tasks.await वापरतो
            val faces = com.google.android.gms.tasks.Tasks.await(
                detector.process(image),
                300,  // 300ms timeout — जास्त वेळ लागल्यास original return
                java.util.concurrent.TimeUnit.MILLISECONDS
            )

            if (faces.isEmpty()) {
                // चेहरा नाही → original bitmap return
                inputBitmap
            } else {
                // Mutable copy बनव — original bitmap वर draw करता येत नाही
                val result = inputBitmap.copy(Bitmap.Config.ARGB_8888, true)
                val canvas = Canvas(result)

                for (face in faces) {
                    drawMask(canvas, face, result.width, result.height)
                }

                // Original recycle — आपण copy बनवली आहे
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

    // ── Mask draw करा ─────────────────────────────────────────────────────
    private fun drawMask(canvas: Canvas, face: Face, imgW: Int, imgH: Int) {
        val bounds = face.boundingBox

        // Bounding box image च्या बाहेर जाऊ नये
        val safeLeft   = bounds.left.coerceAtLeast(0)
        val safeTop    = bounds.top.coerceAtLeast(0)
        val safeRight  = bounds.right.coerceAtMost(imgW)
        val safeBottom = bounds.bottom.coerceAtMost(imgH)

        if (safeRight <= safeLeft || safeBottom <= safeTop) return

        val faceW = safeRight - safeLeft
        val faceH = safeBottom - safeTop

        when (currentFilter) {
            "batman"   -> drawBatmanMask(canvas, safeLeft, safeTop, faceW, faceH)
            "superman" -> drawSupermanMask(canvas, safeLeft, safeTop, faceW, faceH)
            "dog"      -> drawDogFilter(canvas, face, safeLeft, safeTop, faceW, faceH, imgW, imgH)
        }
    }

    // ── Batman Mask ────────────────────────────────────────────────────────
    // Programmatic drawing — PNG asset नसेल तरी काम करतो
    // PNG asset असल्यास खालचा alternative वापर (comments मध्ये)
    private fun drawBatmanMask(canvas: Canvas, x: Int, y: Int, w: Int, h: Int) {

        // ── PNG asset approach (recommended) ─────────────────────────────
        // तुमच्याकडे batman_mask.png असेल तर हे uncomment करा:
        //
        // val maskBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.batman_mask)
        // val scaled = Bitmap.createScaledBitmap(maskBitmap, w, (h * 1.3f).toInt(), true)
        // val destRect = RectF(
        //     x.toFloat(),
        //     (y - h * 0.15f),   // थोडं वर — कारण mask डोक्यापर्यंत जातो
        //     (x + w).toFloat(),
        //     (y + h * 1.1f)
        // )
        // canvas.drawBitmap(scaled, null, destRect, maskPaint)
        // maskBitmap.recycle()
        // scaled.recycle()
        // return
        // ──────────────────────────────────────────────────────────────────

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 1) मुख्य काळा mask — चेहऱ्याचा वरचा 70% भाग
        paint.color = Color.argb(230, 10, 10, 10)  // जवळजवळ काळा, थोडा transparent
        val maskPath = Path().apply {
            val cx = x + w / 2f

            // Batman mask shape — डोळ्यांच्या खाली नाकापर्यंत
            moveTo(x.toFloat(), y + h * 0.25f)
            cubicTo(
                x.toFloat(), y.toFloat(),
                cx - w * 0.1f, y - h * 0.05f,
                cx, y - h * 0.05f
            )
            cubicTo(
                cx + w * 0.1f, y - h * 0.05f,
                (x + w).toFloat(), y.toFloat(),
                (x + w).toFloat(), y + h * 0.25f
            )
            // खाली — नाकापर्यंत
            cubicTo(
                (x + w).toFloat(), y + h * 0.55f,
                cx + w * 0.35f, y + h * 0.6f,
                cx, y + h * 0.58f
            )
            cubicTo(
                cx - w * 0.35f, y + h * 0.6f,
                x.toFloat(), y + h * 0.55f,
                x.toFloat(), y + h * 0.25f
            )
            close()
        }
        canvas.drawPath(maskPath, paint)

        // 2) Bat ears — डोक्यावर
        paint.color = Color.argb(240, 10, 10, 10)
        val leftEar = Path().apply {
            moveTo(x + w * 0.18f, y + h * 0.08f)
            lineTo(x + w * 0.08f, y - h * 0.28f)
            lineTo(x + w * 0.35f, y + h * 0.05f)
            close()
        }
        canvas.drawPath(leftEar, paint)

        val rightEar = Path().apply {
            moveTo(x + w * 0.82f, y + h * 0.08f)
            lineTo(x + w * 0.92f, y - h * 0.28f)
            lineTo(x + w * 0.65f, y + h * 0.05f)
            close()
        }
        canvas.drawPath(rightEar, paint)

        // 3) डोळ्याचे holes — पांढरे (actual eyes दिसाव्यात)
        paint.color = Color.argb(180, 240, 240, 240)
        // डावा डोळा
        val leftEyeRect = RectF(
            x + w * 0.12f, y + h * 0.22f,
            x + w * 0.44f, y + h * 0.40f
        )
        canvas.drawOval(leftEyeRect, paint)

        // उजवा डोळा
        val rightEyeRect = RectF(
            x + w * 0.56f, y + h * 0.22f,
            x + w * 0.88f, y + h * 0.40f
        )
        canvas.drawOval(rightEyeRect, paint)

        // 4) डोळ्यावर पांढरी glow — theatrical effect
        paint.color = Color.argb(200, 255, 255, 255)
        val leftGlowRect = RectF(
            x + w * 0.14f, y + h * 0.24f,
            x + w * 0.42f, y + h * 0.38f
        )
        canvas.drawOval(leftGlowRect, paint)
        val rightGlowRect = RectF(
            x + w * 0.58f, y + h * 0.24f,
            x + w * 0.86f, y + h * 0.38f
        )
        canvas.drawOval(rightGlowRect, paint)
    }

    // ── Superman Mask ──────────────────────────────────────────────────────
    private fun drawSupermanMask(canvas: Canvas, x: Int, y: Int, w: Int, h: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // लाल domino mask
        paint.color = Color.argb(210, 180, 0, 0)
        val maskPath = Path().apply {
            val cx = x + w / 2f

            moveTo(x + w * 0.05f, y + h * 0.28f)
            cubicTo(
                x.toFloat(), y + h * 0.15f,
                x + w * 0.15f, y + h * 0.12f,
                x + w * 0.30f, y + h * 0.18f
            )
            lineTo(x + w * 0.35f, y + h * 0.30f)
            lineTo(cx - w * 0.06f, y + h * 0.30f)
            lineTo(cx + w * 0.06f, y + h * 0.30f)
            lineTo(x + w * 0.65f, y + h * 0.30f)
            lineTo(x + w * 0.70f, y + h * 0.18f)
            cubicTo(
                x + w * 0.85f, y + h * 0.12f,
                (x + w).toFloat(), y + h * 0.15f,
                x + w * 0.95f, y + h * 0.28f
            )
            cubicTo(
                (x + w).toFloat(), y + h * 0.42f,
                x + w * 0.80f, y + h * 0.48f,
                x + w * 0.65f, y + h * 0.44f
            )
            lineTo(cx + w * 0.06f, y + h * 0.44f)
            lineTo(cx - w * 0.06f, y + h * 0.44f)
            lineTo(x + w * 0.35f, y + h * 0.44f)
            cubicTo(
                x + w * 0.20f, y + h * 0.48f,
                x.toFloat(), y + h * 0.42f,
                x + w * 0.05f, y + h * 0.28f
            )
            close()
        }
        canvas.drawPath(maskPath, paint)

        // डोळ्यांचे cutouts — skin color
        paint.color = Color.argb(200, 220, 180, 140)
        val leftCutout = RectF(x + w * 0.10f, y + h * 0.18f, x + w * 0.40f, y + h * 0.40f)
        canvas.drawOval(leftCutout, paint)
        val rightCutout = RectF(x + w * 0.60f, y + h * 0.18f, x + w * 0.90f, y + h * 0.40f)
        canvas.drawOval(rightCutout, paint)

        // S shield — कपाळावर
        val shieldPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        shieldPaint.color = Color.argb(220, 180, 0, 0)
        val shieldRect = RectF(
            x + w * 0.40f, y + h * 0.02f,
            x + w * 0.60f, y + h * 0.16f
        )
        canvas.drawRoundRect(shieldRect, 8f, 8f, shieldPaint)

        // S letter
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(255, 255, 210, 0)
            textSize = w * 0.12f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("S", x + w * 0.50f, y + h * 0.14f, textPaint)
    }

    // ── Dog Filter ─────────────────────────────────────────────────────────
    private fun drawDogFilter(
        canvas: Canvas, face: Face,
        x: Int, y: Int, w: Int, h: Int,
        imgW: Int, imgH: Int
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // कुत्र्याचे कान — बाजूला
        paint.color = Color.argb(220, 180, 100, 40)

        val leftEar = Path().apply {
            moveTo(x + w * 0.05f, y + h * 0.12f)
            cubicTo(
                x - w * 0.20f, y - h * 0.15f,
                x - w * 0.05f, y - h * 0.35f,
                x + w * 0.15f, y - h * 0.10f
            )
            cubicTo(x + w * 0.08f, y + h * 0.05f, x + w * 0.05f, y + h * 0.10f, x + w * 0.05f, y + h * 0.12f)
            close()
        }
        canvas.drawPath(leftEar, paint)

        val rightEar = Path().apply {
            moveTo(x + w * 0.95f, y + h * 0.12f)
            cubicTo(
                x + w * 1.20f, y - h * 0.15f,
                x + w * 1.05f, y - h * 0.35f,
                x + w * 0.85f, y - h * 0.10f
            )
            cubicTo(x + w * 0.92f, y + h * 0.05f, x + w * 0.95f, y + h * 0.10f, x + w * 0.95f, y + h * 0.12f)
            close()
        }
        canvas.drawPath(rightEar, paint)

        // काळं नाक
        paint.color = Color.argb(230, 30, 20, 10)
        val noseOval = RectF(
            x + w * 0.35f, y + h * 0.58f,
            x + w * 0.65f, y + h * 0.74f
        )
        canvas.drawOval(noseOval, paint)

        // गाल चे spots
        paint.color = Color.argb(80, 200, 120, 60)
        canvas.drawCircle(x + w * 0.18f, y + h * 0.70f, w * 0.14f, paint)
        canvas.drawCircle(x + w * 0.82f, y + h * 0.70f, w * 0.14f, paint)

        // freckles
        paint.color = Color.argb(120, 120, 60, 20)
        val freckleR = w * 0.025f
        canvas.drawCircle(x + w * 0.12f, y + h * 0.66f, freckleR, paint)
        canvas.drawCircle(x + w * 0.20f, y + h * 0.72f, freckleR, paint)
        canvas.drawCircle(x + w * 0.14f, y + h * 0.78f, freckleR, paint)
        canvas.drawCircle(x + w * 0.88f, y + h * 0.66f, freckleR, paint)
        canvas.drawCircle(x + w * 0.80f, y + h * 0.72f, freckleR, paint)
        canvas.drawCircle(x + w * 0.86f, y + h * 0.78f, freckleR, paint)

        // जीभ
        paint.color = Color.argb(210, 220, 80, 100)
        val tonguePath = Path().apply {
            moveTo(x + w * 0.35f, y + h * 0.82f)
            cubicTo(
                x + w * 0.30f, y + h * 0.95f,
                x + w * 0.42f, y + h * 1.05f,
                x + w * 0.50f, y + h * 1.08f
            )
            cubicTo(
                x + w * 0.58f, y + h * 1.05f,
                x + w * 0.70f, y + h * 0.95f,
                x + w * 0.65f, y + h * 0.82f
            )
            close()
        }
        canvas.drawPath(tonguePath, paint)
    }

    // ── Cleanup ────────────────────────────────────────────────────────────
    fun release() {
        try {
            detector.close()
            Log.d(TAG, "FaceFilterProcessor released")
        } catch (e: Exception) {
            Log.e(TAG, "Release error: ${e.message}")
        }
    }
}
