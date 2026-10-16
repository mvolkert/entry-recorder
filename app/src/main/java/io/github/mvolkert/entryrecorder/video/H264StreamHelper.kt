package io.github.mvolkert.entryrecorder.video

import java.io.ByteArrayOutputStream

/**
 * Single home for the pure H.264 / Annex-B byte operations shared by the encoding path and the RTSP
 * H.264 **passthrough** capture path (change `rtsp-h264-passthrough`).
 *
 * An RTSP stream's video is already H.264, so a passthrough clip is written by demuxing the elementary
 * stream and handing its Annex-B access units straight to [MkvStreamMuxer] with no decode and no re-encode.
 * The two container-level things that path must get right — the AVCDecoderConfigurationRecord (avcC) that
 * becomes the Matroska `CodecPrivate`, and each sample's keyframe flag — are the same pure byte operations
 * [H264Encoder] already performs on its MediaCodec output, so they live here once and are unit-tested from
 * synthetic NAL bytes with no camera, encoder or Media3 involved.
 */
object H264StreamHelper {

    private const val NAL_TYPE_IDR = 5
    private const val NAL_TYPE_SPS = 7
    private const val NAL_TYPE_PPS = 8

    /** The parameter sets and random-access flag read out of one Annex-B access unit. */
    data class NalSummary(
        val sps: ByteArray?,
        val pps: ByteArray?,
        val containsIdr: Boolean,
    )

    /**
     * Builds an `AVCDecoderConfigurationRecord` (avcC) from raw SPS and PPS NAL units (no start codes).
     * Profile/compat/level come from SPS bytes 1..3; missing trailing bytes read as zero so a truncated
     * parameter set still yields a structurally valid record rather than throwing mid-capture.
     */
    fun buildAvcC(sps: ByteArray, pps: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(sps.size + pps.size + 16)
        out.write(0x01) // configurationVersion
        out.write(sps.getOrElse(1) { 0 }.toInt()) // AVCProfileIndication
        out.write(sps.getOrElse(2) { 0 }.toInt()) // profile_compatibility
        out.write(sps.getOrElse(3) { 0 }.toInt()) // AVCLevelIndication
        out.write(0xFF) // reserved(6) + lengthSizeMinusOne = 3 (4-byte NAL lengths)
        out.write(0xE1) // reserved(3) + numOfSequenceParameterSets = 1
        out.write((sps.size shr 8) and 0xFF)
        out.write(sps.size and 0xFF)
        out.write(sps)
        out.write(0x01) // numOfPictureParameterSets = 1
        out.write((pps.size shr 8) and 0xFF)
        out.write(pps.size and 0xFF)
        out.write(pps)
        return out.toByteArray()
    }

    /**
     * Classifies one access unit: its SPS/PPS (to build the avcC) and whether it holds an IDR slice (the
     * random-access point that should open a new Matroska cluster).
     */
    fun summarize(annexB: ByteArray): NalSummary {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var idr = false
        for (nal in splitNalUnits(annexB)) {
            when (nal.firstOrNull()?.toInt()?.and(0x1F)) {
                NAL_TYPE_SPS -> if (sps == null) sps = nal
                NAL_TYPE_PPS -> if (pps == null) pps = nal
                NAL_TYPE_IDR -> idr = true
            }
        }
        return NalSummary(sps, pps, idr)
    }

    /** Whether the access unit carries an IDR slice, i.e. is a keyframe / random-access point. */
    fun isKeyframe(annexB: ByteArray): Boolean = summarize(annexB).containsIdr

    /**
     * Normalizes a byte stream to Annex-B with 4-byte start codes, regardless of whether it arrived with
     * start codes or as bare length-prefixed / single NAL data.
     */
    fun normalizeToAnnexB(data: ByteArray): ByteArray {
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
    fun splitNalUnits(data: ByteArray): List<ByteArray> {
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
        // No start codes at all: treat the whole buffer as one NAL (a bare CSD or single-slice AU).
        if (nals.isEmpty() && n > 0) nals.add(data)
        return nals.filter { it.isNotEmpty() }
    }

    private fun startCodeLengthAt(data: ByteArray, index: Int): Int {
        val n = data.size
        if (index + 4 <= n &&
            data[index].toInt() == 0 && data[index + 1].toInt() == 0 &&
            data[index + 2].toInt() == 0 && data[index + 3].toInt() == 1
        ) return 4
        if (index + 3 <= n &&
            data[index].toInt() == 0 && data[index + 1].toInt() == 0 &&
            data[index + 2].toInt() == 1
        ) return 3
        return 0
    }
}
