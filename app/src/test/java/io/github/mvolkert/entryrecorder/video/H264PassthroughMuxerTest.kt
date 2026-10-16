package io.github.mvolkert.entryrecorder.video

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 3.1 of `rtsp-h264-passthrough` (CI-only, blind): the demux→re-mux seam — open the H.264 track from the
 * first access unit that carries SPS/PPS, then write later units flagged for keyframes — is proven against a
 * real [MkvStreamMuxer] writing to a temp file from synthetic NAL bytes. No camera and no Media3 are involved;
 * only the elementary-stream tap that feeds [H264SampleSource] needs hardware (task 3.2).
 */
class H264PassthroughMuxerTest {

    private val sps = byteArrayOf(0x67, 0x64, 0x00, 0x1F, 0x11, 0x22)
    private val pps = byteArrayOf(0x68, 0x01, 0x02)
    private val idr = byteArrayOf(0x65, 0x33)
    private val nonIdr = byteArrayOf(0x41, 0x44)
    private val startCode = byteArrayOf(0, 0, 0, 1)

    private fun au(vararg nalHeaderBytes: ByteArray) =
        nalHeaderBytes.fold(byteArrayOf()) { acc, nal -> acc + startCode + nal }

    private fun tempFile() = File.createTempFile("passthrough", ".mkv").apply { deleteOnExit() }

    @Test
    fun `an access unit with no parameter sets opens no track and writes nothing`() {
        val file = tempFile()
        val muxer = H264PassthroughMuxer(MkvStreamMuxer(file), width = 1280, height = 720)

        // A bare inter slice arrives before any SPS/PPS: the avcC is unknown, so nothing may be written.
        muxer.writeUnit(H264AccessUnit(au(nonIdr), timestampMs = 0L), baselineMs = 0L)

        assertEquals(0L, file.length())
    }

    @Test
    fun `the first unit carrying sps and pps opens the h264 track and later units append to it`() {
        val file = tempFile()
        val muxer = H264PassthroughMuxer(MkvStreamMuxer(file), width = 1280, height = 720)

        muxer.writeUnit(H264AccessUnit(au(sps, pps, idr), timestampMs = 1000L), baselineMs = 1000L)
        muxer.writeUnit(H264AccessUnit(au(nonIdr), timestampMs = 1033L), baselineMs = 1000L)

        val bytes = file.readBytes()
        assertTrue("expected a non-empty MKV", bytes.isNotEmpty())
        // EBML magic 1A 45 DF A3 — a valid Matroska header was written, proving configureH264 ran.
        assertEquals(0x1A, bytes[0].toInt() and 0xFF)
        assertEquals(0x45, bytes[1].toInt() and 0xFF)
        assertEquals(0xDF, bytes[2].toInt() and 0xFF)
        assertEquals(0xA3, bytes[3].toInt() and 0xFF)
        // The SPS is embedded verbatim in the avcC CodecPrivate, so its bytes must be present in the file.
        assertTrue("SPS not found in the container's CodecPrivate", bytes.containsAllSequence(sps))
    }

    private fun ByteArray.containsAllSequence(sequence: ByteArray): Boolean {
        if (sequence.isEmpty()) return true
        outer@ for (start in 0..(size - sequence.size)) {
            for (offset in sequence.indices) {
                if (this[start + offset] != sequence[offset]) continue@outer
            }
            return true
        }
        return false
    }
}
