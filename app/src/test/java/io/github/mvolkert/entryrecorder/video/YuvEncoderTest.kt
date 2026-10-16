package io.github.mvolkert.entryrecorder.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM coverage for the two decisions the S4 export fix turned on: which colour format to ask the
 * encoder for, and how ARGB pixels land in a stride/slice-padded frame. Neither needs a `MediaCodec`,
 * so both are deterministic here — the on-device gate only has to prove the produced clip plays.
 */
class YuvEncoderTest {

    // Solid 8-bit colour helpers, ARGB_8888 as the pipeline feeds the packer.
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val red = 0xFFFF0000.toInt()

    @Test
    fun `prefers NV12 when the encoder advertises it`() {
        val chosen = YuvEncoder.selectColorFormat(
            intArrayOf(
                YuvEncoder.FORMAT_YUV420_PLANAR,
                YuvEncoder.FORMAT_YUV420_SEMI_PLANAR,
                YuvEncoder.FORMAT_YUV420_FLEXIBLE,
            )
        )
        assertEquals(YuvEncoder.FORMAT_YUV420_SEMI_PLANAR, chosen?.codecFormat)
        assertEquals(YuvEncoder.Layout.NV12, chosen?.layout)
    }

    @Test
    fun `falls back to planar when NV12 is absent`() {
        val chosen = YuvEncoder.selectColorFormat(
            intArrayOf(YuvEncoder.FORMAT_YUV420_PLANAR, YuvEncoder.FORMAT_YUV420_FLEXIBLE)
        )
        assertEquals(YuvEncoder.FORMAT_YUV420_PLANAR, chosen?.codecFormat)
        assertEquals(YuvEncoder.Layout.I420, chosen?.layout)
    }

    @Test
    fun `uses flexible only when neither concrete layout is offered`() {
        val chosen = YuvEncoder.selectColorFormat(intArrayOf(YuvEncoder.FORMAT_YUV420_FLEXIBLE))
        assertEquals(YuvEncoder.FORMAT_YUV420_FLEXIBLE, chosen?.codecFormat)
    }

    @Test
    fun `reports null when the encoder exposes no YUV420 raw format`() {
        // 13 is COLOR_FormatYUV420PackedPlanar, which this packer does not emit.
        assertNull(YuvEncoder.selectColorFormat(intArrayOf(13)))
    }

    @Test
    fun `frame size matches the padded 420 geometry`() {
        // 4x4 luma + 2x2 chroma in both layouts is 16 + 8 bytes regardless of NV12/I420.
        assertEquals(24, YuvEncoder.frameSize(4, 4, YuvEncoder.Layout.NV12))
        assertEquals(24, YuvEncoder.frameSize(4, 4, YuvEncoder.Layout.I420))
        // A stride wider than the picture pads the buffer accordingly.
        assertEquals(4 * 2 + (4 / 2) * (2 / 2) * 2, YuvEncoder.frameSize(4, 2, YuvEncoder.Layout.NV12))
    }

    @Test
    fun `luma padding edge-replicates the last column and row`() {
        // 2x2 [white black; black white] fed into a stride-4 buffer: the two padding columns copy the
        // last real column of their row, so no synthetic border pixels appear.
        val pixels = intArrayOf(white, black, black, white)
        val out = YuvEncoder.pack(pixels, width = 2, height = 2, stride = 4, sliceHeight = 2, layout = YuvEncoder.Layout.I420)

        // White luma is 235, black is 16 (BT.601 JFIF), matching the rest of the pipeline.
        val expectedRow0 = intArrayOf(235, 16, 16, 16) // col2,col3 replicate col1 (black)
        val expectedRow1 = intArrayOf(16, 235, 235, 235)
        for (i in 0 until 4) assertEquals("row0[$i]", expectedRow0[i], out[i].toInt() and 0xFF)
        for (i in 0 until 4) assertEquals("row1[$i]", expectedRow1[i], out[4 + i].toInt() and 0xFF)
    }

    @Test
    fun `nv12 interleaves UV while i420 keeps separate planes`() {
        // Uniform red: its chroma is U=90, V=240, distinct enough to tell the two layouts apart.
        val pixels = IntArray(16) { red }
        val nv12 = YuvEncoder.pack(pixels, 4, 4, 4, 4, YuvEncoder.Layout.NV12)
        val i420 = YuvEncoder.pack(pixels, 4, 4, 4, 4, YuvEncoder.Layout.I420)

        // Luma plane is 16 bytes; chroma begins at index 16.
        // NV12: U,V,U,V,... so the first V sits at 17.
        assertEquals(90.toByte(), nv12[16])
        assertEquals(240.toByte(), nv12[17])
        // I420: a full U plane then a full V plane, so index 17 is still U and V starts at 20.
        assertEquals(90.toByte(), i420[16])
        assertEquals(90.toByte(), i420[17])
        assertEquals(240.toByte(), i420[20])
    }
}
