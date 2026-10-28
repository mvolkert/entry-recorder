package io.github.mvolkert.entryrecorder.video

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaFormat
import timber.log.Timber
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

    // The raw-input geometry the platform encoder negotiated, honoured when packing every frame.
    // Default to a tightly-packed picture so the first frame is correct even before the format is read.
    private var layout: YuvEncoder.Layout = YuvEncoder.Layout.I420
    private var stride: Int = width
    private var sliceHeight: Int = height

    /** The built `AVCDecoderConfigurationRecord`, or null until SPS/PPS have been observed. */
    val codecPrivate: ByteArray?
        get() {
            val s = sps ?: return null
            val p = pps ?: return null
            return H264StreamHelper.buildAvcC(s, p)
        }

    fun start() {
        val safeFps = fps.coerceIn(1, 30)
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        // Ask the device's encoder which YUV420 layouts it accepts instead of assuming a planar one:
        // hardware that only takes NV12 used to fail configure() here, so the promised H.264 export never
        // happened. Selection order (NV12 -> planar -> flexible) lives in the testable YuvEncoder seam.
        val chosen = encoder.codecInfo
            .getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            .colorFormats
            .let(YuvEncoder::selectColorFormat)
            ?: run {
                encoder.release()
                error("AVC encoder exposes no supported YUV420 input color format")
            }
        layout = chosen.layout

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, chosen.codecFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps.coerceAtLeast(200_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, safeFps)
            // Force a keyframe roughly every second so clusters stay independently seekable and the
            // MKV remains playable after a crash at any point.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        // Honour the stride/slice-height the codec reports for its input; a padded buffer fed as if
        // tightly-packed skews every row after the first. Fall back to the picture size when absent.
        encoder.inputFormat.let { input ->
            stride = input.intOrZero(MediaFormat.KEY_STRIDE) ?: width
            sliceHeight = input.intOrZero(MediaFormat.KEY_SLICE_HEIGHT) ?: height
        }
        encoder.start()
        codec = encoder
        started = true
        Timber.tag(tag).i(
            "H.264 encoder started ${width}x$height @ ${safeFps}fps ${bitrateBps}bps, " +
                "colorFormat=${chosen.codecFormat} layout=$layout stride=$stride sliceHeight=$sliceHeight",
        )
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
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val yuv = YuvEncoder.pack(pixels, width, height, stride, sliceHeight, layout)
            val index = c.dequeueInputBuffer(20_000)
            if (index < 0) {
                // Encoder input momentarily full; drop this frame rather than block the capture loop.
                Timber.tag(tag).d("No input buffer available, dropping frame")
                return
            }
            val inputBuffer = c.getInputBuffer(index) ?: return
            if (yuv.size > inputBuffer.capacity()) {
                // The codec asked for a buffer smaller than the geometry it reported; feeding a
                // mismatched frame would corrupt it, so drop and keep the encoder alive.
                Timber.tag(tag).w("Packed frame ${yuv.size}B exceeds input buffer ${inputBuffer.capacity()}B, dropping frame")
                c.queueInputBuffer(index, 0, 0, presentationTimeUs, 0)
                return
            }
            inputBuffer.clear()
            inputBuffer.put(yuv)
            c.queueInputBuffer(index, 0, yuv.size, presentationTimeUs, 0)
        } catch (e: Exception) {
            Timber.tag(tag).w("Failed to feed input frame: ${e.message}")
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
                    Timber.tag(tag).d("Encoder output format changed: ${c.outputFormat}")
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
                            // Keyframe detection: parse the NAL units for an IDR (type 5). This is the
                            // reliable signal; the deprecated BUFFER_FLAG_SYNC_FRAME hint is intentionally
                            // not used.
                            val isKey = H264StreamHelper.isKeyframe(bytes)
                            result.add(
                                EncodedFrame(
                                    annexB = H264StreamHelper.normalizeToAnnexB(bytes),
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
        for (nal in H264StreamHelper.splitNalUnits(data)) {
            if (nal.isEmpty()) continue
            when (nal[0].toInt() and 0x1F) {
                7 -> sps = nal
                8 -> pps = nal
            }
        }
    }

    /**
     * Reads an optional [MediaFormat] integer key, treating a missing key or a zero (a codec that does not
     * pad) as null so the caller can fall back to the picture geometry.
     */
    private fun MediaFormat.intOrZero(key: String): Int? =
        if (containsKey(key)) getInteger(key).takeIf { it > 0 } else null

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
