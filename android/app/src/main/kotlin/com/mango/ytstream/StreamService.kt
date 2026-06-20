package com.mango.ytstream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class StreamService : Service() {

    companion object {
        var mainActivity: MainActivity? = null
        const val CHANNEL_ID = "ytstream_channel"
        const val NOTIF_ID = 1
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var audioRecord: AudioRecord? = null
    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null

    private val isRunning = AtomicBoolean(false)
    private var rtmpSender: RtmpSender? = null

    private var screenWidth = 1280
    private var screenHeight = 720
    private var screenDensity = 1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopStreaming()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY
        val rtmpUrl = intent.getStringExtra("rtmpUrl") ?: return START_NOT_STICKY
        val streamKey = intent.getStringExtra("streamKey") ?: return START_NOT_STICKY

        startForeground(NOTIF_ID, buildNotification())

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(metrics)
        screenWidth = (metrics.widthPixels / 2) * 2  // ensure even
        screenHeight = (metrics.heightPixels / 2) * 2
        screenDensity = metrics.densityDpi

        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = manager.getMediaProjection(resultCode, data)

        Thread {
            try {
                startStreaming("$rtmpUrl/$streamKey")
            } catch (e: Exception) {
                mainActivity?.notifyFlutter("onStreamError", e.message)
                stopSelf()
            }
        }.start()

        return START_NOT_STICKY
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startStreaming(fullRtmpUrl: String) {
        isRunning.set(true)

        // Setup RTMP sender
        rtmpSender = RtmpSender(fullRtmpUrl, screenWidth, screenHeight)
        rtmpSender!!.connect()

        // Setup video encoder
        setupVideoEncoder()

        // Setup audio encoder + capture
        setupAudioEncoder()

        // Create virtual display for screen capture
        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "YTStream",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            videoEncoder!!.createInputSurface(),
            null, null
        )

        videoEncoder!!.start()
        audioEncoder!!.start()
        audioRecord!!.startRecording()

        mainActivity?.notifyFlutter("onStreamStarted")

        // Start encoding threads
        val videoThread = Thread { encodeVideo() }
        val audioThread = Thread { encodeAudio() }
        videoThread.start()
        audioThread.start()

        videoThread.join()
        audioThread.join()

        mainActivity?.notifyFlutter("onStreamStopped")
    }

    private fun setupVideoEncoder() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, screenWidth, screenHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_500_000) // 2.5 Mbps
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        videoEncoder!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun setupAudioEncoder() {
        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_STEREO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2

        // Capture internal audio (Android 10+)
        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        audioRecord = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setAudioPlaybackCaptureConfig(config)
            .build()

        // AAC encoder
        val audioEncFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize)
        }
        audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        audioEncoder!!.configure(audioEncFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    private fun encodeVideo() {
        val bufferInfo = MediaCodec.BufferInfo()
        while (isRunning.get()) {
            val outputIndex = videoEncoder!!.dequeueOutputBuffer(bufferInfo, 10_000)
            if (outputIndex >= 0) {
                val buffer = videoEncoder!!.getOutputBuffer(outputIndex) ?: continue
                val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                rtmpSender?.sendVideo(buffer, bufferInfo, isKeyFrame)
                videoEncoder!!.releaseOutputBuffer(outputIndex, false)
            }
        }
        videoEncoder!!.stop()
        videoEncoder!!.release()
    }

    private fun encodeAudio() {
        val bufferInfo = MediaCodec.BufferInfo()
        val inputSize = AudioRecord.getMinBufferSize(
            44100, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        ) * 2
        val pcmBuffer = ByteArray(inputSize)

        while (isRunning.get()) {
            // Feed PCM to audio encoder
            val inputIndex = audioEncoder!!.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                val read = audioRecord!!.read(pcmBuffer, 0, pcmBuffer.size)
                if (read > 0) {
                    val inputBuffer = audioEncoder!!.getInputBuffer(inputIndex)!!
                    inputBuffer.clear()
                    inputBuffer.put(pcmBuffer, 0, read)
                    audioEncoder!!.queueInputBuffer(inputIndex, 0, read, System.nanoTime() / 1000, 0)
                }
            }

            // Get encoded audio
            val outputIndex = audioEncoder!!.dequeueOutputBuffer(bufferInfo, 10_000)
            if (outputIndex >= 0) {
                val buffer = audioEncoder!!.getOutputBuffer(outputIndex) ?: continue
                rtmpSender?.sendAudio(buffer, bufferInfo)
                audioEncoder!!.releaseOutputBuffer(outputIndex, false)
            }
        }

        audioRecord!!.stop()
        audioRecord!!.release()
        audioEncoder!!.stop()
        audioEncoder!!.release()
    }

    private fun stopStreaming() {
        isRunning.set(false)
        virtualDisplay?.release()
        mediaProjection?.stop()
        rtmpSender?.disconnect()
        stopForeground(true)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "YT Stream", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Streaming to YouTube" }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🔴 YT Stream - LIVE")
            .setContentText("Streaming screen + internal audio to YouTube")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }
}
