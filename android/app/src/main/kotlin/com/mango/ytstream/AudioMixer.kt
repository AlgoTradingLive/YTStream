package com.mango.ytstream

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.projection.MediaProjection
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioAttributes
import android.os.Build
import androidx.annotation.RequiresApi
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mixes internal audio + microphone into single PCM stream
 * Feeds mixed audio to RtmpDisplay via custom audio effect
 */
class AudioMixer(
    private val mediaProjection: MediaProjection,
    private val sampleRate: Int = 44100,
    private val onMixedAudio: (ByteArray, Int) -> Unit
) {
    private var internalRecord: AudioRecord? = null
    private var micRecord: AudioRecord? = null
    private val isRunning = AtomicBoolean(false)
    private var mixThread: Thread? = null

    private val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_STEREO,
        AudioFormat.ENCODING_PCM_16BIT
    ) * 2

    @RequiresApi(Build.VERSION_CODES.Q)
    fun start() {
        // Internal audio recorder
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        internalRecord = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()

        // Mic recorder
        micRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        internalRecord?.startRecording()
        micRecord?.startRecording()
        isRunning.set(true)

        mixThread = Thread { mixLoop() }.apply { start() }
    }

    private fun mixLoop() {
        val internalBuf = ShortArray(bufferSize / 2)
        val micBuf = ShortArray(bufferSize / 2)
        val mixedBuf = ShortArray(bufferSize / 2)

        while (isRunning.get()) {
            val internalRead = internalRecord?.read(internalBuf, 0, internalBuf.size) ?: 0
            val micRead = micRecord?.read(micBuf, 0, micBuf.size) ?: 0

            val count = maxOf(internalRead, micRead)
            if (count <= 0) continue

            // Mix: add samples and clamp to Short range
            for (i in 0 until count) {
                val inSample = if (i < internalRead) internalBuf[i].toInt() else 0
                val micSample = if (i < micRead) micBuf[i].toInt() else 0
                val mixed = (inSample + micSample).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                mixedBuf[i] = mixed.toShort()
            }

            // Convert to ByteArray
            val byteArray = ByteArray(count * 2)
            for (i in 0 until count) {
                byteArray[i * 2] = (mixedBuf[i].toInt() and 0xFF).toByte()
                byteArray[i * 2 + 1] = ((mixedBuf[i].toInt() shr 8) and 0xFF).toByte()
            }

            onMixedAudio(byteArray, count * 2)
        }
    }

    fun stop() {
        isRunning.set(false)
        mixThread?.join(1000)
        internalRecord?.stop()
        internalRecord?.release()
        micRecord?.stop()
        micRecord?.release()
        internalRecord = null
        micRecord = null
    }
}
