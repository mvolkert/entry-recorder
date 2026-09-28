package io.github.mvolkert.entryrecorder.video

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Pure Kotlin, crash-resilient fragmented-MP4 (fMP4) streaming muxer for H.264 (AVC) video.
 *
 * Rationale: H.264's universally playable container is ISO-BMFF/MP4 — every browser (`<video>`),
 * ExoPlayer, VLC and gallery apps read it, whereas Matroska plays only in lenient players. Like
 * [MkvStreamMuxer] for raw MJPEG capture, this writer is append-only: the `moov` is a template
 * written up front (empty sample tables + `mvex`), and samples live in self-contained
 * `moof`+`mdat` fragment pairs flushed at every keyframe (and forced ~1s without one). A crash
 * loses at most the fragment in progress — never the whole file (no trailing `moov` patch-up).
 *
 * Byte layout mirrors ffmpeg's `-movflags +frag_keyframe+empty_moov+default_base_moof` output:
 * - Timescale 1000 (1 tick = 1 ms), so frame timestamps map 1:1; this pipeline is B-frame-free
 *   (PTS == DTS), so `tfdt` carries the fragment's first presentation time directly.
 * - Samples are packetized AVCC (4-byte big-endian length-prefixed NALs), never Annex-B start
 *   codes — the same framing rule that made the MKV H.264 path pass strict demuxers.
 * - Each `traf` sets `tfhd` flag defaultBaseIsMoof (0x020000), so sample offsets are relative to
 *   the enclosing `moof` and no absolute chunk table exists.
 *
 * API mirrors [MkvStreamMuxer]'s H.264 path so it is a drop-in for the export transcoder only;
 * raw MJPEG capture keeps using the MKV writer.
 */
class Fmp4StreamMuxer(
    outputFile: File,
    private val width: Int = 1280,
    private val height: Int = 720,
    fps: Int = 5,
    private val trackId: Int = 1,
) : AutoCloseable {

    private val tag = "Fmp4StreamMuxer"
    private var fos: FileOutputStream? = null
    private var isHeaderWritten = false

    private val defaultSampleDurationMs = (1000L / fps.coerceIn(1, 30)).coerceAtLeast(1L)

    // Current fragment: samples buffered in memory so moof+mdat land as one atomic pair.
    private val samplePayloads = ArrayList<ByteArray>()
    private val sampleFlags = ArrayList<Int>()
    private var fragmentStartMs = 0L
    private var sequenceNumber = 0

    init {
        outputFile.parentFile?.mkdirs()
        fos = FileOutputStream(outputFile)
        // Header is written on configureH264() because the avcC is only known then.
    }

    /**
     * Sets the `AVCDecoderConfigurationRecord` (built by [H264Encoder] from the encoder's SPS/PPS)
     * and writes the `ftyp`+`moov` template. Must be called before the first [writeH264Frame].
     */
    @Synchronized
    fun configureH264(codecPrivate: ByteArray, width: Int, height: Int) {
        if (isHeaderWritten) return
        val stream = fos ?: return
        try {
            writeFull(stream, "ftyp", ftypPayload())
            writeFull(stream, "moov", moovPayload(codecPrivate, width, height))
            stream.flush()
            isHeaderWritten = true
        } catch (e: Exception) {
            Log.e(tag, "Failed to write fMP4 header", e)
        }
    }

    /**
     * Appends one access unit (Annex-B in, AVCC stored). A new fragment is started at every
     * keyframe; without a keyframe the pending fragment is force-flushed once ~1s have elapsed,
     * keeping the crash window as small as the encoder's KEY_I_FRAME_INTERVAL=1 guarantee.
     */
    @Synchronized
    fun writeH264Frame(annexBByteStream: ByteArray, timestampMs: Long, isKeyframe: Boolean) {
        val stream = fos ?: return
        if (!isHeaderWritten) return
        if (samplePayloads.isNotEmpty() && (isKeyframe || timestampMs - fragmentStartMs >= 1000L)) {
            flushFragment(stream)
        }
        if (samplePayloads.isEmpty()) fragmentStartMs = timestampMs

        val avcc = annexBToLengthPrefixed(annexBByteStream)
        if (avcc.isEmpty()) return
        samplePayloads.add(avcc)
        // sample_depends_on = 2 (intra/I) marks a sync sample, 1 (inter/P) a dependent one.
        // Carried per sample (trun flag 0x000400) so players seek on fragment-leading keyframes.
        sampleFlags.add(if (isKeyframe) SYNC_SAMPLE_FLAGS else DEPENDENT_SAMPLE_FLAGS)
    }

    /** Writes the buffered samples as one `moof`+`mdat` pair and clears the buffer. */
    @Synchronized
    fun flushFragment() {
        val stream = fos ?: return
        flushFragment(stream)
    }

    @Synchronized
    private fun flushFragment(stream: OutputStream) {
        if (samplePayloads.isEmpty()) return
        try {
            val payloadSize = samplePayloads.sumOf { it.size }
            writeFull(stream, "moof", moofPayload(payloadSize))
            writeMdat(stream, payloadSize)
            samplePayloads.forEach { stream.write(it) }
            stream.flush()
        } catch (e: Exception) {
            Log.e(tag, "Error flushing fragment to disk", e)
        } finally {
            samplePayloads.clear()
            sampleFlags.clear()
            sequenceNumber++
        }
    }

    // ---- fragment payloads ----

    private fun moofPayload(mdatPayloadSize: Int): ByteArray {
        val mfhd = u32(sequenceNumber)                                  // fragment_sequence_number

        // tfhd: defaultBaseIsMoof + default_sample_duration + default_sample_size + default_sample_flags.
        // Carrying the defaults here (as ffmpeg's frag_keyframe output does) is what makes strict
        // demuxers turn each trun into index entries; a minimal tfhd yields a well-formed but
        // unplayable file (ffmpeg reads the boxes yet emits 0 packets).
        val tfhdBody = ba(
            u32(trackId),                                                // track_ID
            u32(defaultSampleDurationMs.toInt()),                        // default_sample_duration (ms)
            u32(0),                                                      // default_sample_size (given per sample)
            u32(DEPENDENT_SAMPLE_FLAGS)                                  // default_sample_flags (P-frames)
        )
        val tfdt = u64(fragmentStartMs)                                  // baseMediaDecodeTime (ms timescale)

        // trun flags: data_offset(0x1) + first_sample_flags(0x4) + sample_size(0x200) + sample_cts(0x800).
        // Each fragment starts on a keyframe (frag_keyframe), so first_sample_flags carries the sync
        // marker; the remaining samples inherit DEPENDENT_SAMPLE_FLAGS from tfhd. B-frame-free, so
        // every composition-time offset is 0 (PTS == DTS).
        val firstSampleFlags = if (sampleFlags.isEmpty()) DEPENDENT_SAMPLE_FLAGS else sampleFlags[0]
        // Fixed box sizes give moof = 104 + n*8, so data_offset = moofSize + 8 (mdat header) = 112 + n*8.
        val dataOffset = 112 + sampleFlags.size * 8
        val trun = ByteArrayOutputStream().apply {
            write(u32(samplePayloads.size))                              // sample_count
            write(i32(dataOffset))                                       // data_offset
            write(u32(firstSampleFlags))                                 // first_sample_flags
            for (i in samplePayloads.indices) {
                write(u32(samplePayloads[i].size))                       // sample_size
                write(u32(0))                                            // sample_composition_time_offset
            }
        }
        val traf = ba(
            box("tfhd", fullBody(0, TFHD_FLAGS, tfhdBody)),
            box("tfdt", fullBody(1, 0, tfdt)),
            box("trun", fullBody(0, TRUN_FLAGS, trun.toByteArray()))
        )
        return ba(box("mfhd", fullBody(0, 0, mfhd)), box("traf", traf))
    }

    // ---- header payloads ----

    private fun ftypPayload(): ByteArray = ba(
        ascii("isom"), u32(0x200), ascii("iso2"), ascii("avc1"), ascii("mp42")
    )

    private fun moovPayload(codecPrivate: ByteArray, w: Int, h: Int): ByteArray {
        val mvhd = ByteArrayOutputStream().apply {
            write(u32(0)); write(u32(0))                                 // creation/modification time
            write(u32(TIMECODE_SCALE_MS)); write(u32(0))                 // timescale, duration (live)
            write(u32(0x00010000)); write(u16(0x0100)); write(u16(0))    // rate, volume, reserved
            write(u32(0)); write(u32(0))                                 // reserved[2]
            write(identityMatrix3x3())                                   // 3x3 matrix
            write(ByteArray(24))                                         // padding to the canonical 108-byte mvhd
            write(u32(2))                                                // next_track_ID
        }
        val tkhd = ByteArrayOutputStream().apply {
            write(u32(0)); write(u32(0))                                 // creation/modification time
            write(u32(trackId)); write(u32(0)); write(u32(0))            // track_ID, reserved, duration
            write(u32(0)); write(u32(0))                                 // reserved[2]
            write(u16(0)); write(u16(0)); write(u16(0)); write(u16(0))   // layer, alternate_group, volume(0=video), reserved
            write(identityMatrix3x3())
            write(u32(w shl 16)); write(u32(h shl 16))                  // 16.16 fixed-point dimensions
        }
        val mdhd = ba(u32(0), u32(0), u32(TIMECODE_SCALE_MS), u32(0), u16(0x55C4), u16(0))
        val hdlr = ba(u32(0), ascii("vide"), u32(0), u32(0), u32(0), 0)  // handler_type='vide', name=""
        val vmhd = ba(u16(0), ByteArray(6))                               // graphicsmode=copy, opcolor=0/0/0
        val url = box("url ", u32(0x00000001))                           // self-contained, flag=1
        val dref = box("dref", ba(u32(0), u32(1), url))
        val dinf = box("dinf", dref)
        val stbl = ba(
            box("stsd", fullBody(0, 0, u32(1), avc1SampleEntry(codecPrivate, w, h))),
            box("stts", fullBody(0, 0, u32(0))),                         // empty tables (fragmented)
            box("stsc", fullBody(0, 0, u32(0))),
            box("stsz", fullBody(0, 0, u32(0), u32(0))),
            box("stco", fullBody(0, 0, u32(0)))
        )
        val minf = ba(box("vmhd", fullBody(0, 1, vmhd)), dinf, box("stbl", stbl))
        val mdia = ba(
            box("mdhd", fullBody(0, 0, mdhd)),
            box("hdlr", fullBody(0, 0, hdlr)),
            box("minf", minf)
        )
        val trak = ba(
            box("tkhd", fullBody(0, TKHD_FLAGS_ENABLED, tkhd.toByteArray())),
            mdia
        )
        val trex = ba(
            u32(trackId), u32(1),                                        // track_ID, default_sample_description_index (1-based -> stsd entry 1)
            u32(defaultSampleDurationMs.toInt()),                        // default_sample_duration (ms)
            u32(0),                                                      // default_sample_size (given per sample in trun)
            u32(DEPENDENT_SAMPLE_FLAGS)                                  // default_sample_flags
        )
        val mvex = box("mvex", box("trex", fullBody(0, 0, trex)))
        return ba(
            box("mvhd", fullBody(0, 0, mvhd.toByteArray())),
            box("trak", trak),
            mvex
        )
    }

    /** ISO 14496-15 `avc1` sample entry with the mandatory `avcC` child. */
    private fun avc1SampleEntry(codecPrivate: ByteArray, w: Int, h: Int): ByteArray {
        val name = "H.264".toByteArray(Charsets.US_ASCII)
        val paddedName = ByteArray(31).also { name.copyInto(it, 0, 0, minOf(name.size, 31)) }
        val entryBody = ba(
            ByteArray(6), u16(1),                                        // reserved(6) + data_reference_index
            u16(0), u16(0), ByteArray(12),                               // pre_defined, reserved, compressor predefined
            u16(w), u16(h),                                              // width, height
            u32(0x00480000), u32(0x00480000),                            // horiz/vert resolution 72 dpi
            u32(0),                                                      // reserved
            u16(1),                                                      // frame_count
            name.size, paddedName,                                       // 32-byte pascal string compressorname
            u16(0x0018),                                                 // bit_depth = 24
            u16(0xFFFF),                                                 // color table = 0xFFFF (no LUT)
            box("avcC", codecPrivate)
        )
        return box("avc1", entryBody)
    }

    // ---- Annex-B to AVCC (mirrors MkvStreamMuxer's rule: MKV blocks and MP4 samples are both
    // length-prefixed; strict demuxers misread start codes as bogus sample lengths) ----

    private fun annexBToLengthPrefixed(annexB: ByteArray): ByteArray {
        val nals = extractNalUnits(annexB)
        if (nals.isEmpty()) return annexB
        val out = ByteArrayOutputStream(annexB.size + nals.size * 4)
        for (nal in nals) {
            out.write(uint32Bytes(nal.size))
            out.write(nal)
        }
        return out.toByteArray()
    }

    private fun extractNalUnits(data: ByteArray): List<ByteArray> {
        val nals = ArrayList<ByteArray>()
        val n = data.size
        var i = 0
        var nalStart = -1
        while (i < n) {
            val sc = startCodeLengthAt(data, i)
            if (sc > 0) {
                if (nalStart in 0..<i) nals.add(data.copyOfRange(nalStart, i))
                i += sc
                nalStart = i
            } else {
                i++
            }
        }
        if (nalStart in 0 until n) nals.add(data.copyOfRange(nalStart, n))
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

    // ---- low-level ISO-BMFF box primitives (32-bit sizes; mdat falls back to 64-bit) ----

    private fun writeFull(stream: OutputStream, type: String, payload: ByteArray) {
        stream.write(uint32Bytes(8 + payload.size))
        stream.write(type.toByteArray(Charsets.US_ASCII))
        stream.write(payload)
    }

    private fun writeMdat(stream: OutputStream, payloadSize: Int) {
        if (payloadSize + 8L > 0xFFFFFFF0L) {
            stream.write(uint32Bytes(1))
            stream.write("mdat".toByteArray(Charsets.US_ASCII))
            stream.write(uint64Bytes(16L + payloadSize))          // 64-bit size includes the 16-byte header
        } else {
            stream.write(uint32Bytes(8 + payloadSize))            // header size spans the payload that follows
            stream.write("mdat".toByteArray(Charsets.US_ASCII))
        }
    }

    private fun box(type: String, payload: ByteArray): ByteArray =
        ba(uint32Bytes(8 + payload.size), type.toByteArray(Charsets.US_ASCII), payload)

    private fun fullBody(version: Int, flags: Int, vararg parts: Any): ByteArray =
        ba(uint32Bytes((version shl 24) or (flags and 0xFFFFFF)), *parts)

    private fun u32(v: Int): ByteArray = uint32Bytes(v)
    private fun u32(v: Long): ByteArray = uint32Bytes(v.toInt())
    private fun i32(v: Int): ByteArray = uint32Bytes(v)
    private fun u64(v: Long): ByteArray = uint64Bytes(v)
    private fun u16(v: Int): ByteArray = byteArrayOf(((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte())
    private fun ascii(s: String): ByteArray = s.toByteArray(Charsets.US_ASCII)

    /** 3x3 transform matrix in 16.16/2.30 fixed point (identity, as mvhd/tkhd require). */
    private fun identityMatrix3x3(): ByteArray = ba(
        u32(0x00010000), u32(0), u32(0),
        u32(0), u32(0x00010000), u32(0),
        u32(0), u32(0), u32(0x40000000)
    )

    private fun uint32Bytes(value: Int): ByteArray = byteArrayOf(
        ((value ushr 24) and 0xFF).toByte(), ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(), (value and 0xFF).toByte()
    )

    private fun uint64Bytes(value: Long): ByteArray = byteArrayOf(
        ((value ushr 56) and 0xFF).toByte(), ((value ushr 48) and 0xFF).toByte(),
        ((value ushr 40) and 0xFF).toByte(), ((value ushr 32) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte(), ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(), (value and 0xFF).toByte()
    )

    private fun ba(vararg parts: Any): ByteArray {
        val out = ByteArrayOutputStream()
        for (p in parts) when (p) {
            is ByteArray -> out.write(p)
            is Int -> out.write(p.toByte().toInt())
            else -> error("Unsupported box part type: $p")
        }
        return out.toByteArray()
    }

    override fun close() {
        try {
            flushFragment()
            fos?.flush()
            fos?.close()
        } catch (_: Exception) {}
        fos = null
    }

    companion object {
        private const val TIMECODE_SCALE_MS = 1000
        // tfhd: default_base_is_moof(0x20000) | default_sample_duration(0x08) | default_sample_size(0x10)
        //        | default_sample_flags(0x20)
        private const val TFHD_FLAGS = 0x020000 or 0x000008 or 0x000010 or 0x000020
        // trun: data_offset(0x1) | first_sample_flags(0x4) | sample_size(0x200) | sample_cts_offset(0x800)
        private const val TRUN_FLAGS = 0x000001 or 0x000004 or 0x000200 or 0x000800
        private const val TKHD_FLAGS_ENABLED = 0x000003                      // enabled | in movie
        private const val SYNC_SAMPLE_FLAGS = 0x02000000                     // sample_depends_on=2 (I-frame)
        private const val DEPENDENT_SAMPLE_FLAGS = 0x01010000                // sample_depends_on=1 (P-frame), non-sync
    }
}
