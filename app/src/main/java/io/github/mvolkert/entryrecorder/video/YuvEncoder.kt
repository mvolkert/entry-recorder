package io.github.mvolkert.entryrecorder.video

/**
 * The device-independent half of the H.264 encoder's raw-input handling: which colour format to ask the
 * platform encoder for, and how to pack ARGB pixels into that format honouring its stride and slice height.
 *
 * Kept free of `android.media` so both decisions are unit-testable on the JVM without a codec.
 * [H264Encoder] supplies the framework constants and the `MediaCodec` buffers around it.
 *
 * The old code hardcoded [FORMAT_YUV420_PLANAR] and assumed a tightly-packed frame, which some hardware
 * encoders (a Galaxy Tab S4 among them) reject outright — the export then promised an H.264 clip it could
 * never produce. Selecting a format the encoder actually lists, and padding to its reported geometry, is
 * the fix.
 */
object YuvEncoder {

    // MediaCodecInfo.CodecCapabilities constants, inlined as literals so the selection is testable off
    // device. These are fixed public API values, not per-device negotiated ones.
    const val FORMAT_YUV420_PLANAR = 19 // COLOR_FormatYUV420Planar (I420: Y, then U plane, then V plane)
    const val FORMAT_YUV420_SEMI_PLANAR = 21 // COLOR_FormatYUV420SemiPlanar (NV12: Y, then UV interleaved)
    const val FORMAT_YUV420_FLEXIBLE = 0x7F420888 // COLOR_FormatYUV420Flexible (2141391496)

    /** The two packed layouts this packer can emit; the flexible format is fed as I420. */
    enum class Layout { NV12, I420 }

    /** A colour format the encoder supports, paired with the layout [pack] must emit for it. */
    data class Chosen(val codecFormat: Int, val layout: Layout)

    /**
     * Picks the raw input colour format from [supported] (a codec's `colorFormats`), preferring NV12, then
     * planar, then the flexible fallback. NV12 first because it has the widest hardware-encoder coverage —
     * including the encoder that failed the planar-only assumption — and flexible last because it lets the
     * platform choose a concrete layout we then mirror via stride/slice height. Returns null when the
     * encoder advertises none of the three, so the caller can surface that precise reason instead of a
     * generic encode failure.
     */
    fun selectColorFormat(supported: IntArray): Chosen? = when {
        supported.any { it == FORMAT_YUV420_SEMI_PLANAR } -> Chosen(FORMAT_YUV420_SEMI_PLANAR, Layout.NV12)
        supported.any { it == FORMAT_YUV420_PLANAR } -> Chosen(FORMAT_YUV420_PLANAR, Layout.I420)
        supported.any { it == FORMAT_YUV420_FLEXIBLE } -> Chosen(FORMAT_YUV420_FLEXIBLE, Layout.I420)
        else -> null
    }

    /**
     * Byte size of a frame in [layout] for the given geometry, exactly as a `MediaCodec` raw input buffer
     * of that stride/slice height expects it. Chroma is 4:2:0: one sample pair per 2x2 luma block, and both
     * the chroma row length and row count follow from the (not the picture's) stride and slice height.
     */
    fun frameSize(stride: Int, sliceHeight: Int, layout: Layout): Int {
        val luma = stride * sliceHeight
        // 4:2:0 is half-area chroma in both layouts: NV12 stores U,V interleaved (2 bytes per chroma
        // pixel), I420 stores a U plane and a V plane (1 byte each) — the same byte total.
        val chromaBytes = (stride / 2) * (sliceHeight / 2) * 2
        return luma + chromaBytes
    }

    /**
     * Converts [pixels] (ARGB_8888, [width]x[height] row-major) into a YUV420 frame of [stride] x
     * [sliceHeight] in [layout]. Any stride/slice padding is edge-replicated for luma and neutral-chroma
     * for chroma, so a buffer larger than the picture does not introduce green borders. [BT601] JFIF
     * coefficients match the rest of the pipeline.
     */
    fun pack(
        pixels: IntArray,
        width: Int,
        height: Int,
        stride: Int,
        sliceHeight: Int,
        layout: Layout,
    ): ByteArray {
        val out = ByteArray(frameSize(stride, sliceHeight, layout))
        val chromaStride = stride / 2
        val chromaRows = sliceHeight / 2

        // Luma plane, edge-replicated into the stride/slice padding.
        for (row in 0 until sliceHeight) {
            val srcY = row.coerceAtMost(height - 1) * width
            val dstRow = row * stride
            for (col in 0 until stride) {
                val color = pixels[srcY + col.coerceAtMost(width - 1)]
                out[dstRow + col] = lumaOf(color)
            }
        }

        val lumaSize = stride * sliceHeight
        val uOffset = lumaSize
        val vOffset = lumaSize + chromaStride * chromaRows

        // 4:2:0 chroma: one sample per 2x2 luma block, averaged to suppress ringing at edges.
        for (cy in 0 until chromaRows) {
            for (cx in 0 until chromaStride) {
                val r = IntArray(4)
                val g = IntArray(4)
                val b = IntArray(4)
                var n = 0
                for (dy in 0 until 2) {
                    for (dx in 0 until 2) {
                        val px = ((cy * 2 + dy).coerceAtMost(height - 1)) * width +
                            (cx * 2 + dx).coerceAtMost(width - 1)
                        val color = pixels[px]
                        r[n] = (color shr 16) and 0xFF
                        g[n] = (color shr 8) and 0xFF
                        b[n] = color and 0xFF
                        n++
                    }
                }
                val rAvg = r.sum() / n
                val gAvg = g.sum() / n
                val bAvg = b.sum() / n
                val u = chromaU(rAvg, gAvg, bAvg)
                val v = chromaV(rAvg, gAvg, bAvg)
                when (layout) {
                    Layout.NV12 -> {
                        val at = lumaSize + cy * chromaStride * 2 + cx * 2
                        out[at] = u
                        out[at + 1] = v
                    }
                    Layout.I420 -> {
                        val at = cy * chromaStride + cx
                        out[uOffset + at] = u
                        out[vOffset + at] = v
                    }
                }
            }
        }
        return out
    }

    private fun lumaOf(color: Int): Byte {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
        return y.coerceIn(0, 255).toByte()
    }

    private fun chromaU(r: Int, g: Int, b: Int): Byte =
        (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()

    private fun chromaV(r: Int, g: Int, b: Int): Byte =
        (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
}
