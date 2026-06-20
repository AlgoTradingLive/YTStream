package com.mango.ytstream

import android.media.MediaCodec
import java.io.DataInputStream
import java.io.OutputStream
import java.net.Socket
import java.net.URI
import java.nio.ByteBuffer
import java.security.SecureRandom

/**
 * Minimal RTMP sender — implements just enough of the RTMP spec
 * to stream H.264 video + AAC audio to YouTube Live.
 *
 * Protocol: RTMP (port 1935)
 * Chunk size: 4096
 */
class RtmpSender(
    private val fullUrl: String,   // e.g. rtmp://a.rtmp.youtube.com/live2/xxxx-key
    private val width: Int,
    private val height: Int
) {
    private var socket: Socket? = null
    private var output: OutputStream? = null
    private var input: DataInputStream? = null

    private var chunkStreamId = 4
    private var streamId = 1
    private var startTimeMs = 0L
    private var sequenceNumber = 0

    // SPS/PPS from first video keyframe
    private var spsData: ByteArray? = null
    private var ppsData: ByteArray? = null
    private var videoHeaderSent = false
    private var audioHeaderSent = false

    fun connect() {
        // Parse URL: rtmp://host/app/streamKey
        val uri = URI(fullUrl)
        val host = uri.host
        val port = if (uri.port == -1) 1935 else uri.port
        val pathParts = uri.path.trimStart('/').split("/", limit = 2)
        val app = pathParts.getOrElse(0) { "live2" }
        val streamKey = pathParts.getOrElse(1) { "" }

        socket = Socket(host, port)
        socket!!.tcpNoDelay = true
        output = socket!!.getOutputStream()
        input = DataInputStream(socket!!.getInputStream())

        doHandshake()
        sendConnect(app, "rtmp://$host/$app")
        sendCreateStream()
        sendPublish(streamKey)

        startTimeMs = System.currentTimeMillis()
    }

    // ─── RTMP Handshake (C0+C1+C2 / S0+S1+S2) ───────────────────────────────

    private fun doHandshake() {
        val out = output!!
        val inp = input!!

        // C0: version = 3
        out.write(3)

        // C1: time (4 bytes) + zeros (4 bytes) + random (1528 bytes)
        val c1 = ByteArray(1536)
        val rng = SecureRandom()
        rng.nextBytes(c1)
        c1[0] = 0; c1[1] = 0; c1[2] = 0; c1[3] = 0  // time = 0
        c1[4] = 0; c1[5] = 0; c1[6] = 0; c1[7] = 0   // zeros
        out.write(c1)
        out.flush()

        // Read S0
        inp.read()

        // Read S1 (1536 bytes)
        val s1 = ByteArray(1536)
        inp.readFully(s1)

        // Read S2 (1536 bytes)
        val s2 = ByteArray(1536)
        inp.readFully(s2)

        // C2: echo S1
        out.write(s1)
        out.flush()
    }

    // ─── AMF helpers ─────────────────────────────────────────────────────────

    private fun amfString(s: String): ByteArray {
        val bytes = s.toByteArray(Charsets.UTF_8)
        return byteArrayOf(0x02) + shortToBytes(bytes.size.toShort()) + bytes
    }

    private fun amfDouble(d: Double): ByteArray {
        val bits = java.lang.Double.doubleToLongBits(d)
        return byteArrayOf(0x00) + longToBytes(bits)
    }

    private fun amfBool(b: Boolean): ByteArray = byteArrayOf(0x01, if (b) 1 else 0)

    private fun amfNull(): ByteArray = byteArrayOf(0x05)

    private fun amfObject(vararg pairs: Pair<String, ByteArray>): ByteArray {
        var result = byteArrayOf(0x03)
        for ((k, v) in pairs) {
            val kb = k.toByteArray(Charsets.UTF_8)
            result += shortToBytes(kb.size.toShort()) + kb + v
        }
        result += byteArrayOf(0x00, 0x00, 0x09) // end marker
        return result
    }

    // ─── RTMP chunk sending ──────────────────────────────────────────────────

    private fun sendChunk(csId: Int, type: Int, timestamp: Int, streamId: Int, payload: ByteArray) {
        val out = output!!

        // Basic header (fmt=0, chunk stream id)
        out.write(csId and 0x3F)

        // Message header (fmt=0: 11 bytes)
        out.write((timestamp shr 16) and 0xFF)
        out.write((timestamp shr 8) and 0xFF)
        out.write(timestamp and 0xFF)
        out.write((payload.size shr 16) and 0xFF)
        out.write((payload.size shr 8) and 0xFF)
        out.write(payload.size and 0xFF)
        out.write(type)
        // Stream ID (little-endian)
        out.write(streamId and 0xFF)
        out.write((streamId shr 8) and 0xFF)
        out.write((streamId shr 16) and 0xFF)
        out.write((streamId shr 24) and 0xFF)

        // Send payload in chunks of 128 bytes (default chunk size)
        var offset = 0
        val chunkSize = 128
        while (offset < payload.size) {
            if (offset > 0) out.write(0xC0 or (csId and 0x3F)) // fmt=3
            val end = minOf(offset + chunkSize, payload.size)
            out.write(payload, offset, end - offset)
            offset = end
        }
        out.flush()
    }

    private fun sendAmfCommand(payload: ByteArray, ts: Int = 0) {
        sendChunk(3, 0x14, ts, 0, payload)
    }

    private fun sendConnect(app: String, tcUrl: String) {
        val payload =
            amfString("connect") +
            amfDouble(1.0) +
            amfObject(
                "app" to amfString(app),
                "type" to amfString("nonprivate"),
                "tcUrl" to amfString(tcUrl),
                "flashVer" to amfString("FMLE/3.0 (compatible; FMSc/1.0)")
            )
        sendAmfCommand(payload)
        readAndDiscardUntilResult("_result")
    }

    private fun sendCreateStream() {
        val payload = amfString("createStream") + amfDouble(2.0) + amfNull()
        sendAmfCommand(payload)
        readAndDiscardUntilResult("_result")
    }

    private fun sendPublish(streamKey: String) {
        val payload =
            amfString("publish") +
            amfDouble(3.0) +
            amfNull() +
            amfString(streamKey) +
            amfString("live")
        sendAmfCommand(payload)
        // Don't wait for response — YouTube sends it async
    }

    // Basic response reader — reads chunks until we see the expected command name
    private fun readAndDiscardUntilResult(expected: String) {
        val inp = input!!
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            try {
                // Read basic header
                val b0 = inp.read()
                if (b0 == -1) break
                val fmt = (b0 shr 6) and 0x03
                val csId = b0 and 0x3F

                // Only handle fmt=0 for simplicity
                if (fmt == 0) {
                    val ts0 = inp.read(); val ts1 = inp.read(); val ts2 = inp.read()
                    val len0 = inp.read(); val len1 = inp.read(); val len2 = inp.read()
                    val msgType = inp.read()
                    val sid0 = inp.read(); val sid1 = inp.read(); val sid2 = inp.read(); val sid3 = inp.read()
                    val length = (len0 shl 16) or (len1 shl 8) or len2

                    val data = ByteArray(length)
                    // Read in 128-byte chunks
                    var read = 0
                    while (read < length) {
                        val toRead = minOf(128, length - read)
                        inp.readFully(data, read, toRead)
                        read += toRead
                        if (read < length) inp.read() // discard continuation byte
                    }

                    // Check if it contains our expected string
                    val str = String(data)
                    if (str.contains(expected)) return
                }
            } catch (e: Exception) {
                break
            }
        }
    }

    // ─── Video sending ───────────────────────────────────────────────────────

    fun sendVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo, isKeyFrame: Boolean) {
        val data = ByteArray(info.size)
        buffer.position(info.offset)
        buffer.get(data, 0, info.size)

        // Extract SPS/PPS from first keyframe
        if (!videoHeaderSent && isKeyFrame) {
            extractSpsAndPps(data)
            if (spsData != null && ppsData != null) {
                sendAvcDecoderConfigRecord()
                videoHeaderSent = true
            }
        }

        if (!videoHeaderSent) return

        val ts = ((System.currentTimeMillis() - startTimeMs)).toInt()

        // Build FLV video tag
        val naluData = annexBToAvcc(data)
        val frameType = if (isKeyFrame) 0x17 else 0x27

        val payload = byteArrayOf(
            frameType.toByte(),
            0x01,  // AVC NALU
            0x00, 0x00, 0x00  // composition time
        ) + naluData

        sendChunk(4, 0x09, ts, streamId, payload)
    }

    private fun sendAvcDecoderConfigRecord() {
        val sps = spsData!!
        val pps = ppsData!!

        val record = byteArrayOf(
            0x01,           // version
            sps[1], sps[2], sps[3],  // profile, compatibility, level
            0xFF.toByte(),  // NAL length minus one = 3 (4 bytes per length)
            0xE1.toByte(),  // number of SPS = 1
        ) + shortToBytes(sps.size.toShort()) + sps +
        byteArrayOf(0x01) +  // number of PPS = 1
        shortToBytes(pps.size.toShort()) + pps

        val payload = byteArrayOf(
            0x17,  // keyframe + AVC
            0x00,  // AVC sequence header
            0x00, 0x00, 0x00  // composition time
        ) + record

        val ts = ((System.currentTimeMillis() - startTimeMs)).toInt()
        sendChunk(4, 0x09, ts, streamId, payload)
    }

    private fun extractSpsAndPps(annexBData: ByteArray) {
        // Find NAL units separated by 0x00000001
        val nalus = mutableListOf<ByteArray>()
        var start = 0
        var i = 0
        while (i < annexBData.size - 4) {
            if (annexBData[i] == 0.toByte() && annexBData[i+1] == 0.toByte() &&
                annexBData[i+2] == 0.toByte() && annexBData[i+3] == 1.toByte()) {
                if (i > start) nalus.add(annexBData.copyOfRange(start, i))
                start = i + 4
                i += 4
            } else if (annexBData[i] == 0.toByte() && annexBData[i+1] == 0.toByte() &&
                       annexBData[i+2] == 1.toByte()) {
                if (i > start) nalus.add(annexBData.copyOfRange(start, i))
                start = i + 3
                i += 3
            } else i++
        }
        if (start < annexBData.size) nalus.add(annexBData.copyOfRange(start, annexBData.size))

        for (nalu in nalus) {
            if (nalu.isEmpty()) continue
            val naluType = nalu[0].toInt() and 0x1F
            when (naluType) {
                7 -> spsData = nalu
                8 -> ppsData = nalu
            }
        }
    }

    private fun annexBToAvcc(annexBData: ByteArray): ByteArray {
        // Convert annex-B (0001 start codes) to AVCC (4-byte length prefix)
        val nalus = mutableListOf<ByteArray>()
        var start = 0
        var i = 0
        while (i < annexBData.size - 4) {
            if (annexBData[i] == 0.toByte() && annexBData[i+1] == 0.toByte() &&
                annexBData[i+2] == 0.toByte() && annexBData[i+3] == 1.toByte()) {
                if (i > start) nalus.add(annexBData.copyOfRange(start, i))
                start = i + 4; i += 4
            } else if (annexBData[i] == 0.toByte() && annexBData[i+1] == 0.toByte() &&
                       annexBData[i+2] == 1.toByte()) {
                if (i > start) nalus.add(annexBData.copyOfRange(start, i))
                start = i + 3; i += 3
            } else i++
        }
        if (start < annexBData.size) nalus.add(annexBData.copyOfRange(start, annexBData.size))

        var result = ByteArray(0)
        for (nalu in nalus) {
            if (nalu.isEmpty()) continue
            val naluType = nalu[0].toInt() and 0x1F
            if (naluType == 7 || naluType == 8) continue // skip SPS/PPS in stream
            result += intToBytes(nalu.size) + nalu
        }
        return result
    }

    // ─── Audio sending ───────────────────────────────────────────────────────

    fun sendAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val data = ByteArray(info.size)
        buffer.position(info.offset)
        buffer.get(data, 0, info.size)

        if (!audioHeaderSent) {
            sendAacSequenceHeader()
            audioHeaderSent = true
        }

        val ts = ((System.currentTimeMillis() - startTimeMs)).toInt()

        // FLV audio tag: AAC raw
        val payload = byteArrayOf(
            0xAF.toByte(),  // AAC, 44100, stereo, 16-bit
            0x01            // AAC raw
        ) + data

        sendChunk(5, 0x08, ts, streamId, payload)
    }

    private fun sendAacSequenceHeader() {
        // AAC sequence header for 44100Hz stereo LC
        // AudioSpecificConfig: profile=2 (LC), freq=4 (44100), channels=2
        val asc = byteArrayOf(
            0x12,  // 00010 (LC=2) 010 (44100=4 index) 0...
            0x10   // ...0 0010 (stereo=2) 000
        )

        val payload = byteArrayOf(
            0xAF.toByte(),  // AAC, 44100, stereo, 16-bit
            0x00            // AAC sequence header
        ) + asc

        val ts = ((System.currentTimeMillis() - startTimeMs)).toInt()
        sendChunk(5, 0x08, ts, streamId, payload)
    }

    // ─── Disconnect ──────────────────────────────────────────────────────────

    fun disconnect() {
        try { output?.close() } catch (_: Exception) {}
        try { input?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
    }

    // ─── Byte helpers ────────────────────────────────────────────────────────

    private fun shortToBytes(v: Short): ByteArray = byteArrayOf(
        ((v.toInt() shr 8) and 0xFF).toByte(),
        (v.toInt() and 0xFF).toByte()
    )

    private fun intToBytes(v: Int): ByteArray = byteArrayOf(
        ((v shr 24) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte()
    )

    private fun longToBytes(v: Long): ByteArray = byteArrayOf(
        ((v shr 56) and 0xFF).toByte(),
        ((v shr 48) and 0xFF).toByte(),
        ((v shr 40) and 0xFF).toByte(),
        ((v shr 32) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte()
    )
}
