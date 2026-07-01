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
 * Filters: galaxy_blue | galaxy_green | galaxy_red |
 *          anonymous | robot | tribal | webslinger | tiger | holi | none
 */
class FaceFilterProcessor(private val context: Context) {

    companion object {
        private const val TAG = "FaceFilterProcessor"
    }

    @Volatile private var currentFilter = "none"
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
            Log.w(TAG, "Timeout — skipping frame"); inputBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Process error: ${e.message}"); inputBitmap
        } finally {
            isProcessing.set(false)
        }
    }

    private fun drawMask(canvas: Canvas, face: Face, imgW: Int, imgH: Int) {
        val b = face.boundingBox
        val x = b.left.coerceAtLeast(0)
        val y = b.top.coerceAtLeast(0)
        val w = (b.right.coerceAtMost(imgW)) - x
        val h = (b.bottom.coerceAtMost(imgH)) - y
        if (w <= 0 || h <= 0) return

        when (currentFilter) {
            "galaxy_blue"  -> drawGalaxy(canvas, x, y, w, h, eyeColor = 0xFF0066FF.toInt(), glowColor = 0xFF00AAFF.toInt(), outerGlow = 0xFF88DDFF.toInt(), pupilColor = 0xFF001844.toInt(), bgColors = intArrayOf(0xFF050a2a.toInt(), 0xFF010510.toInt()), starTint = 0xFFAADDFF.toInt())
            "galaxy_green" -> drawGalaxy(canvas, x, y, w, h, eyeColor = 0xFF00AA44.toInt(), glowColor = 0xFF00FF88.toInt(), outerGlow = 0xFFAAFFCC.toInt(), pupilColor = 0xFF001A08.toInt(), bgColors = intArrayOf(0xFF041A0A.toInt(), 0xFF010801.toInt()), starTint = 0xFFAAFFCC.toInt())
            "galaxy_red"   -> drawGalaxy(canvas, x, y, w, h, eyeColor = 0xFFCC0000.toInt(), glowColor = 0xFFFF2222.toInt(), outerGlow = 0xFFFF9999.toInt(), pupilColor = 0xFF1A0000.toInt(), bgColors = intArrayOf(0xFF1A0404.toInt(), 0xFF080101.toInt()), starTint = 0xFFFFAAAA.toInt())
            "anonymous"    -> drawAnonymousMask(canvas, x, y, w, h)
            "robot"        -> drawRobotMask(canvas, x, y, w, h)
            "tribal"       -> drawTribalMask(canvas, x, y, w, h)
            "webslinger"   -> drawWebslingerMask(canvas, x, y, w, h)
            "tiger"        -> drawTigerMask(canvas, x, y, w, h)
            "holi"         -> drawHoliFilter(canvas, x, y, w, h)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GALAXY MASK — अंडाकार/लांबट background (Blue / Green / Red)
    // ═══════════════════════════════════════════════════════════════════════
    private fun drawGalaxy(
        canvas: Canvas, x: Int, y: Int, w: Int, h: Int,
        eyeColor: Int, glowColor: Int, outerGlow: Int, pupilColor: Int,
        bgColors: IntArray, starTint: Int
    ) {
        val cx = x + w / 2f
        val cy = y + h / 2f

        // ── अंडाकार / लांबट dark background ──────────────────────────────
        // चेहऱ्यापेक्षा रुंदीत 20% मोठा, उंचीत 30% मोठा — खाली जास्त extend
        val bgW = w * 0.60f   // rx — रुंदी
        val bgH = h * 0.68f   // ry — उंची (लांबट साठी जास्त)
        val bgCy = y + h * 0.45f  // center थोडं खाली — खालचा भाग जास्त cover

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, bgCy, bgH,
                bgColors, null,
                Shader.TileMode.CLAMP
            )
            alpha = 247
        }
        canvas.drawOval(RectF(cx - bgW, bgCy - bgH, cx + bgW, bgCy + bgH), bgPaint)

        // ── Stars ──────────────────────────────────────────────────────────
        val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val starPositions = listOf(
            0.22f to 0.08f, 0.42f to 0.02f, 0.78f to 0.05f, 0.88f to 0.18f,
            0.10f to 0.30f, 0.92f to 0.35f, 0.50f to 0.00f, 0.15f to 0.72f,
            0.85f to 0.78f, 0.60f to 0.88f, 0.30f to 0.92f, 0.72f to 0.95f,
            0.05f to 0.55f, 0.95f to 0.60f, 0.48f to 0.96f
        )
        for ((sx, sy) in starPositions) {
            val starX = cx - bgW + (bgW * 2 * sx)
            val starY = bgCy - bgH + (bgH * 2 * sy)
            // alternating white and tinted stars
            starPaint.color = if (sx > 0.5f) Color.WHITE else starTint
            starPaint.alpha = 180 + (sx * 60).toInt()
            canvas.drawCircle(starX, starY, w * 0.007f, starPaint)
        }

        // ── Glowing eyes ──────────────────────────────────────────────────
        val eyeY = y + h * 0.40f
        val eyeRx = w * 0.20f   // eye width
        val eyeRy = h * 0.10f   // eye height

        val leftEyeX  = x + w * 0.28f
        val rightEyeX = x + w * 0.72f

        // outer soft glow
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = outerGlow; alpha = 60
        }
        canvas.drawOval(RectF(leftEyeX - eyeRx * 1.4f, eyeY - eyeRy * 1.5f, leftEyeX + eyeRx * 1.4f, eyeY + eyeRy * 1.5f), glowPaint)
        canvas.drawOval(RectF(rightEyeX - eyeRx * 1.4f, eyeY - eyeRy * 1.5f, rightEyeX + eyeRx * 1.4f, eyeY + eyeRy * 1.5f), glowPaint)

        // main eye fill
        val eyeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = eyeColor; alpha = 242 }
        canvas.drawOval(RectF(leftEyeX - eyeRx, eyeY - eyeRy, leftEyeX + eyeRx, eyeY + eyeRy), eyeFill)
        canvas.drawOval(RectF(rightEyeX - eyeRx, eyeY - eyeRy, rightEyeX + eyeRx, eyeY + eyeRy), eyeFill)

        // neon ring
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = glowColor; style = Paint.Style.STROKE; strokeWidth = w * 0.012f; alpha = 200
        }
        canvas.drawOval(RectF(leftEyeX - eyeRx, eyeY - eyeRy, leftEyeX + eyeRx, eyeY + eyeRy), ringPaint)
        canvas.drawOval(RectF(rightEyeX - eyeRx, eyeY - eyeRy, rightEyeX + eyeRx, eyeY + eyeRy), ringPaint)

        // pupil
        val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pupilColor }
        canvas.drawCircle(leftEyeX, eyeY, eyeRx * 0.28f, pupilPaint)
        canvas.drawCircle(rightEyeX, eyeY, eyeRx * 0.28f, pupilPaint)

        // ── Nebula accent lines ────────────────────────────────────────────
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = glowColor; strokeWidth = w * 0.006f; alpha = 90
        }
        canvas.drawLine(cx - bgW * 0.7f, bgCy + bgH * 0.35f, cx + bgW * 0.7f, bgCy + bgH * 0.35f, linePaint)
        canvas.drawLine(cx - bgW * 0.6f, bgCy - bgH * 0.72f, cx + bgW * 0.6f, bgCy - bgH * 0.72f, linePaint)

        // side glow lines
        linePaint.alpha = 100
        canvas.drawLine(x - w * 0.05f, bgCy + bgH * 0.15f, x + w * 0.12f, bgCy + bgH * 0.12f, linePaint)
        canvas.drawLine(x + w * 1.05f, bgCy + bgH * 0.15f, x + w * 0.88f, bgCy + bgH * 0.12f, linePaint)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ANONYMOUS MASK
    // ═══════════════════════════════════════════════════════════════════════
    private fun drawAnonymousMask(canvas: Canvas, x: Int, y: Int, w: Int, h: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.argb(255, 237, 230, 216)
        val path = Path().apply {
            val cx = x + w / 2f
            moveTo(x - w * 0.05f, y + h * 0.05f)
            cubicTo(x - w * 0.08f, y - h * 0.10f, cx, y - h * 0.15f, x + w * 1.05f, y + h * 0.05f)
            lineTo(x + w * 1.05f, y + h * 1.05f)
            cubicTo(x + w * 0.75f, y + h * 1.20f, cx, y + h * 1.15f, x - w * 0.05f, y + h * 1.05f); close()
        }
        canvas.drawPath(path, paint)
        paint.color = Color.argb(255, 40, 40, 40)
        canvas.drawRoundRect(RectF(x + w * 0.10f, y + h * 0.30f, x + w * 0.42f, y + h * 0.38f), 6f, 6f, paint)
        canvas.drawRoundRect(RectF(x + w * 0.58f, y + h * 0.30f, x + w * 0.90f, y + h * 0.38f), 6f, 6f, paint)
        paint.color = Color.argb(140, 184, 172, 146)
        canvas.drawLine(x + w * 0.46f, y + h * 0.42f, x + w * 0.44f, y + h * 0.62f, paint)
        paint.color = Color.argb(180, 184, 172, 146)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f
        canvas.drawPath(Path().apply {
            moveTo(x + w * 0.28f, y + h * 0.78f); quadTo(x + w * 0.50f, y + h * 0.88f, x + w * 0.72f, y + h * 0.78f)
        }, paint)
        paint.style = Paint.Style.FILL
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ROBOT MASK
    // ═══════════════════════════════════════════════════════════════════════
    private fun drawRobotMask(canvas: Canvas, x: Int, y: Int, w: Int, h: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.argb(255, 154, 163, 171)
        canvas.drawPath(Path().apply {
            moveTo(x - w * 0.03f, y + h * 0.08f)
            cubicTo(x.toFloat(), y - h * 0.10f, x + w * 0.5f, y - h * 0.18f, x + w * 1.03f, y + h * 0.08f)
            cubicTo(x + w * 1.10f, y + h * 0.45f, x + w * 0.95f, y + h * 0.85f, x + w * 0.5f, y + h * 1.10f)
            cubicTo(x + w * 0.05f, y + h * 0.85f, x - w * 0.10f, y + h * 0.45f, x - w * 0.03f, y + h * 0.08f); close()
        }, paint)
        paint.color = Color.argb(255, 26, 31, 36)
        canvas.drawRoundRect(RectF(x + w * 0.10f, y + h * 0.28f, x + w * 0.44f, y + h * 0.50f), 6f, 6f, paint)
        canvas.drawRoundRect(RectF(x + w * 0.56f, y + h * 0.28f, x + w * 0.90f, y + h * 0.50f), 6f, 6f, paint)
        paint.color = Color.argb(255, 62, 198, 255)
        canvas.drawCircle(x + w * 0.27f, y + h * 0.39f, w * 0.07f, paint)
        canvas.drawCircle(x + w * 0.73f, y + h * 0.39f, w * 0.07f, paint)
        paint.color = Color.argb(255, 26, 31, 36)
        canvas.drawRoundRect(RectF(x + w * 0.30f, y + h * 0.74f, x + w * 0.70f, y + h * 0.84f), 5f, 5f, paint)
        paint.color = Color.argb(255, 62, 198, 255)
        for (i in 0..2) { val s = x + w * (0.34f + i * 0.12f); canvas.drawRect(s, y + h * 0.77f, s + w * 0.07f, y + h * 0.81f, paint) }
        paint.color = Color.argb(180, 93, 102, 110)
        canvas.drawLine(x + w * 0.10f, y + h * 0.18f, x + w * 0.90f, y + h * 0.18f, paint)
        canvas.drawLine(x + w * 0.05f, y + h * 0.58f, x + w * 0.95f, y + h * 0.58f, paint)
        paint.color = Color.argb(255, 62, 198, 255)
        canvas.drawCircle(x + w * 0.5f, y + h * 0.0f, w * 0.04f, paint)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TRIBAL MASK
    // ═══════════════════════════════════════════════════════════════════════
    private fun drawTribalMask(canvas: Canvas, x: Int, y: Int, w: Int, h: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.argb(235, 26, 26, 46)
        val path = Path().apply {
            moveTo(x + w * 0.05f, y + h * 0.10f)
            cubicTo(x + w * 0.10f, y - h * 0.05f, x + w * 0.5f, y - h * 0.12f, x + w * 0.95f, y + h * 0.10f)
            cubicTo(x + w * 1.02f, y + h * 0.45f, x + w * 0.92f, y + h * 0.82f, x + w * 0.5f, y + h * 1.08f)
            cubicTo(x + w * 0.08f, y + h * 0.82f, x - w * 0.02f, y + h * 0.45f, x + w * 0.05f, y + h * 0.10f); close()
        }
        canvas.drawPath(path, paint)
        paint.color = Color.argb(255, 0, 229, 212); paint.style = Paint.Style.STROKE; paint.strokeWidth = w * 0.012f
        canvas.drawPath(path, paint)
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
        canvas.drawPath(Path().apply { moveTo(x + w * 0.32f, y + h * 0.78f); quadTo(x + w * 0.5f, y + h * 0.86f, x + w * 0.68f, y + h * 0.78f) }, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(255, 0, 229, 212)
        canvas.drawCircle(x + w * 0.27f, y + h * 0.40f, w * 0.012f, paint)
        canvas.drawCircle(x + w * 0.73f, y + h * 0.40f, w * 0.012f, paint)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // WEB-SLINGER MASK
    // ═══════════════════════════════════════════════════════════════════════
    private fun drawWebslingerMask(canvas: Canvas, x: Int, y: Int, w: Int, h: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.argb(255, 200, 16, 46)
        val path = Path().apply {
            moveTo(x + w * 0.05f, y + h * 0.08f)
            cubicTo(x + w * 0.10f, y - h * 0.08f, x + w * 0.5f, y - h * 0.18f, x + w * 0.95f, y + h * 0.08f)
            cubicTo(x + w * 1.04f, y + h * 0.45f, x + w * 0.92f, y + h * 0.85f, x + w * 0.5f, y + h * 1.10f)
            cubicTo(x + w * 0.08f, y + h * 0.85f, x - w * 0.04f, y + h * 0.45f, x + w * 0.05f, y + h * 0.08f); close()
        }
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = w * 0.01f; paint.color = Color.argb(255, 10, 31, 110)
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL; paint.color = Color.argb(255, 254, 254, 254)
        canvas.drawOval(RectF(x + w * 0.10f, y + h * 0.30f, x + w * 0.42f, y + h * 0.55f), paint)
        canvas.drawOval(RectF(x + w * 0.58f, y + h * 0.30f, x + w * 0.90f, y + h * 0.55f), paint)
        paint.color = Color.argb(255, 10, 31, 110); paint.style = Paint.Style.STROKE; paint.strokeWidth = w * 0.006f
        canvas.drawOval(RectF(x + w * 0.10f, y + h * 0.30f, x + w * 0.42f, y + h * 0.55f), paint)
        canvas.drawOval(RectF(x + w * 0.58f, y + h * 0.30f, x + w * 0.90f, y + h * 0.55f), paint)
        for (ecx in listOf(x + w * 0.26f, x + w * 0.74f)) {
            val ecy = y + h * 0.42f
            canvas.drawLine(ecx, ecy - h * 0.08f, ecx, ecy + h * 0.08f, paint)
            canvas.drawLine(ecx - w * 0.13f, ecy, ecx + w * 0.13f, ecy, paint)
            canvas.drawLine(ecx - w * 0.09f, ecy - h * 0.06f, ecx + w * 0.09f, ecy + h * 0.06f, paint)
            canvas.drawLine(ecx + w * 0.09f, ecy - h * 0.06f, ecx - w * 0.09f, ecy + h * 0.06f, paint)
        }
        paint.color = Color.argb(180, 10, 31, 110)
        canvas.drawLine(x + w * 0.5f, y - h * 0.10f, x + w * 0.5f, y + h * 1.05f, paint)
        canvas.drawLine(x.toFloat(), y + h * 0.05f, x + w * 0.30f, y + h * 0.30f, paint)
        canvas.drawLine((x + w).toFloat(), y + h * 0.05f, x + w * 0.70f, y + h * 0.30f, paint)
        paint.style = Paint.Style.FILL
    }

    // ═══════════════════════════════════════════════════════════════════════
    // NEON TIGER MASK
    // ═══════════════════════════════════════════════════════════════════════
    private fun drawTigerMask(canvas: Canvas, x: Int, y: Int, w: Int, h: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.argb(255, 26, 26, 26)
        listOf(
            floatArrayOf(x + w * 0.27f, y + h * 0.36f, x + w * 0.20f, y + h * 0.30f, x + w * 0.32f, y + h * 0.28f),
            floatArrayOf(x + w * 0.73f, y + h * 0.36f, x + w * 0.80f, y + h * 0.30f, x + w * 0.68f, y + h * 0.28f),
            floatArrayOf(x + w * 0.41f, y + h * 0.34f, x + w * 0.37f, y + h * 0.28f, x + w * 0.46f, y + h * 0.26f),
            floatArrayOf(x + w * 0.59f, y + h * 0.34f, x + w * 0.63f, y + h * 0.28f, x + w * 0.54f, y + h * 0.26f),
            floatArrayOf(x + w * 0.21f, y + h * 0.60f, x + w * 0.13f, y + h * 0.58f, x + w * 0.24f, y + h * 0.52f),
            floatArrayOf(x + w * 0.79f, y + h * 0.60f, x + w * 0.87f, y + h * 0.58f, x + w * 0.76f, y + h * 0.52f)
        ).forEach { p -> canvas.drawPath(Path().apply { moveTo(p[0], p[1]); lineTo(p[2], p[3]); lineTo(p[4], p[5]); close() }, paint) }

        paint.color = Color.argb(255, 255, 0, 0)
        canvas.drawOval(RectF(x + w * 0.12f, y + h * 0.32f, x + w * 0.42f, y + h * 0.49f), paint)
        canvas.drawOval(RectF(x + w * 0.58f, y + h * 0.32f, x + w * 0.88f, y + h * 0.49f), paint)
        paint.color = Color.argb(140, 255, 80, 80); paint.style = Paint.Style.STROKE; paint.strokeWidth = w * 0.025f
        canvas.drawOval(RectF(x + w * 0.12f, y + h * 0.32f, x + w * 0.42f, y + h * 0.49f), paint)
        canvas.drawOval(RectF(x + w * 0.58f, y + h * 0.32f, x + w * 0.88f, y + h * 0.49f), paint)
        paint.style = Paint.Style.FILL; paint.color = Color.argb(255, 50, 0, 0)
        canvas.drawCircle(x + w * 0.27f, y + h * 0.405f, w * 0.025f, paint)
        canvas.drawCircle(x + w * 0.73f, y + h * 0.405f, w * 0.025f, paint)
        paint.color = Color.argb(255, 26, 26, 26)
        canvas.drawPath(Path().apply {
            moveTo(x + w * 0.5f, y + h * 0.55f); lineTo(x + w * 0.45f, y + h * 0.61f)
            quadTo(x + w * 0.5f, y + h * 0.65f, x + w * 0.55f, y + h * 0.61f); close()
        }, paint)
        paint.color = Color.argb(220, 255, 255, 255); paint.strokeWidth = w * 0.008f
        canvas.drawLine(x + w * 0.30f, y + h * 0.65f, x - w * 0.05f, y + h * 0.62f, paint)
        canvas.drawLine(x + w * 0.30f, y + h * 0.68f, x - w * 0.05f, y + h * 0.70f, paint)
        canvas.drawLine(x + w * 0.70f, y + h * 0.65f, x + w * 1.05f, y + h * 0.62f, paint)
        canvas.drawLine(x + w * 0.70f, y + h * 0.68f, x + w * 1.05f, y + h * 0.70f, paint)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HOLI FILTER
    // ═══════════════════════════════════════════════════════════════════════
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
            Triple(0.45f, 0.42f, 0.03f)
        )
        for ((i, s) in spots.withIndex()) {
            paint.color = colors[i % colors.size]
            canvas.drawCircle(x + w * s.first, y + h * s.second, w * s.third, paint)
        }
    }

    fun release() {
        try { detector.close(); Log.d(TAG, "Released") } catch (e: Exception) { Log.e(TAG, "Release error: ${e.message}") }
    }
}
