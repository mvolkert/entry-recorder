package io.github.mvolkert.entryrecorder.video

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.io.ByteArrayOutputStream
import androidx.core.graphics.scale

/**
 * Encodes a sequence of JPEG frames into H.264 (AVC) Annex-B access units using [MediaCodec].
 *
 * Rationale: MJPEG (`V_MJPEG`) tracks are not decodable by Android's video pipeline, so recordings
 * could not be played back in-app. Encoding the captured snapshot/MJPEG frames to H.264 produces a
 * standard, hardware-decodable elementary stream that [MkvStreamMuxer] wraps in a crash-resilient
 * MKV container.
 *
 * The encoder output is normalized to Annex-B (each NAL prefixed with a 4-byte start code) so it
 * matches the format [MkvStreamMuxer] writes for `V_MPEG4/ISO/AVC` blocks. The AVCDecoder
 * Configuration Record (avcC) required as the Matroska `CodecPrivate` is built from the encoder's
 * SPS/PPS codec-specific buffers (available once [.codecPrivate] is non-null).
 */
class H264Encoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrateBps: Int,
) : AutoCloseable {

    data class EncodedFrame(
        val annexB: ByteArray,
        val presentationTimeUs: Long,
        val isKeyframe: Boolean,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as EncodedFrame

            if (presentationTimeUs != other.presentationTimeUs) return false
            if (isKeyframe != other.isKeyframe) return false
            if (!annexB.contentEquals(other.annexB)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = presentationTimeUs.hashCode()
            result = 31 * result + isKeyframe.hashCode()
            result = 31 * result + annexB.contentHashCode()
            return result
        }
    }

    private val tag = "H264Encoder"

    private var codec: MediaCodec? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var started = false

    /** The built `AVCDecoderConfigurationRecord`, or null until SPS/PPS have been observed. */
    val codecPrivate: ByteArray?
        get() {
            val s = sps ?: return null
            val p = pps ?: return null
            return buildAvcC(s, p)
        }

    fun start() {
        val safeFps = fps.coerceIn(1, 30)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps.coerceAtLeast(200_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, safeFps)
            // Force a keyframe roughly every second so clusters stay independently seekable and the
            // MKV remains playable after a crash at any point.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        started = true
        Log.i(tag, "H.264 encoder started ${width}x$height @ ${safeFps}fps ${bitrateBps}bps")
    }

    /**
     * Encodes one JPEG frame. Returns 0..n Annex-B access units produced for this input.
     * [presentationTimeUs] is the timestamp assigned to the input frame.
     */
    fun encode(jpegBytes: ByteArray, presentationTimeUs: Long): List<EncodedFrame> {
        if (!started) return emptyList()
        val c = codec ?: return emptyList()

        feedInput(c, jpegBytes, presentationTimeUs)
        return drainOutputs(c, endOfStream = false)
    }

    /**
     * Signals end-of-stream and returns any remaining encoded frames.
     */
    fun drain(): List<EncodedFrame> {
        val c = codec ?: return emptyList()
        val index = c.dequeueInputBuffer(20_000)
        if (index >= 0) {
            c.queueInputBuffer(index, 0, 0, System.nanoTime() / 1000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
        return drainOutputs(c, endOfStream = true)
    }

    private fun feedInput(c: MediaCodec, jpegBytes: ByteArray, presentationTimeUs: Long) {
        val bitmap = decodeToBitmap(jpegBytes) ?: return
        try {
            val i420 = bitmapToI420(bitmap)
            val index = c.dequeueInputBuffer(20_000)
            if (index < 0) {
                // Encoder input momentarily full; drop this frame rather than block the capture loop.
                Log.d(tag, "No input buffer available, dropping frame")
                return
            }
            val inputBuffer = c.getInputBuffer(index) ?: return
            inputBuffer.clear()
            inputBuffer.put(i420)
            c.queueInputBuffer(index, 0, i420.size, presentationTimeUs, 0)
        } catch (e: Exception) {
            Log.w(tag, "Failed to feed input frame: ${e.message}")
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeToBitmap(jpegBytes: ByteArray): Bitmap? {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val decoded = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, opts) ?: return null
        if (decoded.width == width && decoded.height == height) return decoded
        val scaled = decoded.scale(width, height)
        if (scaled != decoded) decoded.recycle()
        return scaled
    }

    private fun drainOutputs(c: MediaCodec, endOfStream: Boolean): List<EncodedFrame> {
        val result = ArrayList<EncodedFrame>()
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val outIndex = c.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (endOfStream) return result
                    return result
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.d(tag, "Encoder output format changed: ${c.outputFormat}")
                }
                outIndex >= 0 -> {
                    val outBuffer = c.getOutputBuffer(outIndex)
                    if (outBuffer != null && bufferInfo.size > 0) {
                        outBuffer.position(bufferInfo.offset)
                        outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val bytes = ByteArray(bufferInfo.size)
                        outBuffer.get(bytes)

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            extractParameterSets(bytes)
                        } else {
                            // Some encoders carry SPS/PPS in-band with the first IDR instead of a
                            // dedicated CODEC_CONFIG buffer; learn them from any access unit.
                            extractParameterSets(bytes)
                            val isKey = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_SYNC_FRAME) != 0 ||
                                containsIdr(bytes)
                            result.add(
                                EncodedFrame(
                                    annexB = normalizeToAnnexB(bytes),
                                    presentationTimeUs = bufferInfo.presentationTimeUs,
                                    isKeyframe = isKey,
                                )
                            )
                        }
                    }
                    c.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        return result
                    }
                }
            }
        }
    }

    /**
     * MediaCodec AVC CSD buffers may be Annex-B or raw NALs; split them into NAL units and store
     * the SPS (type 7) and PPS (type 8).
     */
    private fun extractParameterSets(data: ByteArray) {
        for (nal in splitNalUnits(data)) {
            if (nal.isEmpty()) continue
            when (nal[0].toInt() and 0x1F) {
                7 -> sps = nal
                8 -> pps = nal
            }
        }
    }

    private fun containsIdr(data: ByteArray): Boolean =
        splitNalUnits(data).any { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 5 }

    /**
     * Normalizes an access unit to Annex-B with 4-byte start codes, regardless of whether the
     * encoder emitted start codes or bare NALs.
     */
    private fun normalizeToAnnexB(data: ByteArray): ByteArray {
        val nals = splitNalUnits(data)
        if (nals.isEmpty()) return data
        val out = ByteArrayOutputStream(data.size + nals.size * 4)
        for (nal in nals) {
            out.write(byteArrayOf(0x00, 0x00, 0x00, 0x01))
            out.write(nal)
        }
        return out.toByteArray()
    }

    /** Splits a byte stream (with or without start codes) into raw NAL units (no start codes). */
    private fun splitNalUnits(data: ByteArray): List<ByteArray> {
        val nals = ArrayList<ByteArray>()
        var i = 0
        val n = data.size
        var start = -1
        while (i < n) {
            val scLen = startCodeLengthAt(data, i)
            if (scLen > 0) {
                if (start >= 0) nals.add(data.copyOfRange(start, i))
                i += scLen
                start = i
            } else {
                i++
            }
        }
        if (start in 0 until n) nals.add(data.copyOfRange(start, n))
        // If no start codes were present at all, treat the whole buffer as one NAL.
        if (nals.isEmpty() && n > 0) nals.add(data)
        return nals.filter { it.isNotEmpty() }
    }

    private fun startCodeLengthAt(data: ByteArray, index: Int): Int {
        val n = data.size
        if (index + 3 <= n &&
            data[index].toInt() == 0 &&
            data[index + 1].toInt() == 0 &&
            data[index + 2].toInt() == 1
        ) {
            return 3
        }
        if (index + 4 <= n &&
            data[index].toInt() == 0 &&
            data[index + 1].toInt() == 0 &&
            data[index + 2].toInt() == 0 &&
            data[index + 3].toInt() == 1
        ) {
            return 4
        }
        return 0
    }

    companion object {
        /**
         * Builds an `AVCDecoderConfigurationRecord` (avcC) from raw SPS and PPS NAL units.
         */
        fun buildAvcC(sps: ByteArray, pps: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(0x01) // configurationVersion
            // AVCProfileIndication / profile_compatibility / AVCLevelIndication come from SPS bytes 1..3
            out.write(sps.getOrElse(1) { 0 }.toInt())
            out.write(sps.getOrElse(2) { 0 }.toInt())
            out.write(sps.getOrElse(3) { 0 }.toInt())
            out.write(0xFF.toByte().toInt()) // lengthSizeMinusOne = 3 (4-byte NAL lengths)
            out.write(0xE1) // numOfSequenceParameterSets = 1
            out.write((sps.size shr 8) and 0xFF)
            out.write(sps.size and 0xFF)
            out.write(sps)
            out.write(0x01) // numOfPictureParameterSets = 1
            out.write((pps.size shr 8) and 0xFF)
            out.write(pps.size and 0xFF)
            out.write(pps)
            return out.toByteArray()
        }
    }

    /**
     * Converts an ARGB_8888 bitmap to a packed I420 (YUV420Planar) buffer of size width*height*3/2.
     */
    private fun bitmapToI420(bitmap: Bitmap): ByteArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val ySize = w * h
        val uvW = w / 2
        val uvH = h / 2
        val uvSize = uvW * uvH
        val i420 = ByteArray(ySize + 2 * uvSize)

        var yIndex = 0
        var uIndex = ySize
        var vIndex = ySize + uvSize

        var j = 0
        while (j < h) {
            var i = 0
            while (i < w) {
                val color = pixels[j * w + i]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                i420[yIndex++] = y.coerceIn(0, 255).toByte()

                // Subsample chroma on even 2x2 blocks.
                if (j % 2 == 0 && i % 2 == 0 && (j + 1) < h && (i + 1) < w) {
                    val c10 = pixels[j * w + (i + 1)]
                    val c01 = pixels[(j + 1) * w + i]
                    val c11 = pixels[(j + 1) * w + (i + 1)]

                    fun avg(ch: (Int) -> Int) =
                        (ch(color) + ch(c10) + ch(c01) + ch(c11)) / 4

                    val rAvg = avg { c -> (c shr 16) and 0xFF }
                    val gAvg = avg { c -> (c shr 8) and 0xFF }
                    val bAvg = avg { c -> c and 0xFF }

                    val u = ((-38 * rAvg - 74 * gAvg + 112 * bAvg + 128) shr 8) + 128
                    val v = ((112 * rAvg - 94 * gAvg - 18 * bAvg + 128) shr 8) + 128

                    i420[uIndex++] = u.coerceIn(0, 255).toByte()
                    i420[vIndex++] = v.coerceIn(0, 255).toByte()
                }
                i++
            }
            j++
        }
        return i420
    }

    override fun close() {
        try {
            codec?.stop()
        } catch (_: Exception) {
        } finally {
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            codec = null
            started = false
        }
    }
}
