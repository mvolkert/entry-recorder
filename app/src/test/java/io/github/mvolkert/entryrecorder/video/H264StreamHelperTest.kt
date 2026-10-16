package io.github.mvolkert.entryrecorder.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 3.1 of `rtsp-h264-passthrough`: the pure half of the zero-re-encode path — turning the RTSP
 * stream's SPS/PPS into the container's avcC and flagging keyframes from the access units — is exercised
 * from synthetic NAL bytes, with no camera or Media3 involved. That demux side cannot be proven without
 * hardware (task 3.2), but everything that does not depend on it can be, and this pins it.
 */
class H264StreamHelperTest {

    // SPS/PPS/slice NALs, header byte then filler; every byte is < 128 so byteArrayOf needs no .toByte().
    private val sps = byteArrayOf(0x67, 0x64, 0x00, 0x1F, 0x11, 0x22)
    private val pps = byteArrayOf(0x68, 0x01, 0x02)
    private val idrSlice = byteArrayOf(0x65, 0x33)
    private val nonIdrSlice = byteArrayOf(0x41, 0x44)

    private fun annexB(vararg nalsWithStartCode: ByteArray) =
        nalsWithStartCode.fold(byteArrayOf()) { acc, b -> acc + b }

    private val startCode4 = byteArrayOf(0, 0, 0, 1)
    private val startCode3 = byteArrayOf(0, 0, 1)

    @Test
    fun `avcC copies profile level and length size and carries sps then pps`() {
        val avcC = H264StreamHelper.buildAvcC(sps, pps)

        fun b(i: Int) = avcC[i].toInt() and 0xFF
        assertEquals(1, b(0))                 // configurationVersion
        assertEquals(0x64, b(1))              // AVCProfileIndication from SPS[1]
        assertEquals(0x00, b(2))              // profile_compatibility from SPS[2]
        assertEquals(0x1F, b(3))              // AVCLevelIndication from SPS[3]
        assertEquals(0xFF, b(4))              // reserved(6) + lengthSizeMinusOne(3) == 4-byte NAL lengths
        assertEquals(0xE1, b(5))              // reserved(3) + numOfSPS == 1

        // 2-byte SPS length then the SPS itself.
        assertEquals(sps.size, (b(6) shl 8) or b(7))
        assertArrayEquals(sps, avcC.copyOfRange(8, 8 + sps.size))

        val ppsCountIndex = 8 + sps.size
        assertEquals(1, b(ppsCountIndex))     // numOfPPS == 1
        val ppsLen = (b(ppsCountIndex + 1) shl 8) or b(ppsCountIndex + 2)
        assertEquals(pps.size, ppsLen)
        assertArrayEquals(pps, avcC.copyOfRange(ppsCountIndex + 3, ppsCountIndex + 3 + pps.size))

        // 8 header bytes (6 config + 2-byte SPS length) + SPS + 3 PPS header bytes + PPS.
        assertEquals(8 + sps.size + 3 + pps.size, avcC.size)
    }

    @Test
    fun `a truncated SPS pads the profile and level bytes with zero rather than throwing`() {
        val avcC = H264StreamHelper.buildAvcC(byteArrayOf(0x67, 0x64), pps)
        fun b(i: Int) = avcC[i].toInt() and 0xFF
        // SPS is only 2 bytes, so the compat (index 2) and level (index 3) source bytes are absent -> zero.
        assertEquals(0x64, b(1))
        assertEquals(0x00, b(2))
        assertEquals(0x00, b(3))
    }

    @Test
    fun `summarize finds sps and pps and flags an IDR across mixed start-code lengths`() {
        val au = annexB(
            startCode4 + sps,
            startCode3 + pps,
            startCode4 + idrSlice,
        )
        val summary = H264StreamHelper.summarize(au)

        assertArrayEquals(sps, summary.sps)
        assertArrayEquals(pps, summary.pps)
        assertTrue(summary.containsIdr)
        assertTrue(H264StreamHelper.isKeyframe(au))
    }

    @Test
    fun `a non-IDR access unit is not a keyframe and carries no parameter sets`() {
        val au = annexB(startCode4 + nonIdrSlice)
        val summary = H264StreamHelper.summarize(au)

        assertFalse(summary.containsIdr)
        assertFalse(H264StreamHelper.isKeyframe(au))
        assertNull(summary.sps)
        assertNull(summary.pps)
    }
}
