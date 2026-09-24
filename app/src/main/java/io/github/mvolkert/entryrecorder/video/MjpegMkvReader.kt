package io.github.mvolkert.entryrecorder.video

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Minimal, dependency-free reader for the crash-safe `V_MJPEG` Matroska files produced by
 * [MkvStreamMuxer].
 *
 * It only understands the exact subset of EBML/Matroska that this app writes:
 * `Segment -> Cluster -> (Timecode | SimpleBlock)`, where each SimpleBlock on track 1 carries one
 * complete JPEG frame. Because we author the files, no generic demuxing or codec handling is needed.
 *
 * Parsing builds a lightweight index of frame byte-ranges ([FrameRef]) so both the in-app player
 * and the export transcoder can random-access individual frames without loading the whole file.
 * Truncated tail clusters (from a crash) are simply not indexed, so playback of the readable part
 * still works.
 */
class MjpegMkvReader(private val file: File) {

    data class FrameRef(val payloadOffset: Long, val payloadSize: Int, val timestampMs: Long)

    private val frames = ArrayList<FrameRef>()
    private var parsed = false

    /** Parses the file (once) and returns the indexed frames in presentation order. */
    @Synchronized
    fun readFrameIndex(): List<FrameRef> {
        if (!parsed) {
            frames.clear()
            runCatching { parse() }
            parsed = true
        }
        return frames
    }

    /** Reads the raw JPEG payload of a single indexed frame. */
    fun readFrame(ref: FrameRef): ByteArray {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(ref.payloadOffset)
            val buf = ByteArray(ref.payloadSize)
            raf.readFully(buf)
            return buf
        }
    }

    private fun parse() {
        val c = CountingStream(BufferedInputStream(FileInputStream(file), 64 * 1024))
        try {
            while (true) {
                val id = c.readId() ?: return
                val size = c.readSize() ?: return
                when (id) {
                    ID_EBML_HEADER -> if (size.isUnknown) return else c.skip(size.value)
                    ID_SEGMENT -> parseSegment(c, size)
                    else -> if (size.isUnknown) return else c.skip(size.value)
                }
            }
        } finally {
            runCatching { c.close() }
        }
    }

    private fun parseSegment(c: CountingStream, size: Vint) {
        val end = if (size.isUnknown) Long.MAX_VALUE else c.position + size.value
        while (c.position < end) {
            val id = c.readId() ?: return
            val childSize = c.readSize() ?: return
            when (id) {
                ID_CLUSTER -> parseCluster(c, childSize, end)
                else -> if (childSize.isUnknown) return else c.skip(childSize.value)
            }
        }
    }

    private fun parseCluster(c: CountingStream, size: Vint, segmentEnd: Long) {
        val end = if (size.isUnknown) segmentEnd else minOf(c.position + size.value, segmentEnd)
        var clusterTimecode = 0L
        while (c.position < end) {
            val id = c.readId() ?: return
            val elSize = c.readSize() ?: return
            if (elSize.isUnknown) return
            when (id) {
                ID_TIMECODE -> clusterTimecode = c.readUint(elSize.value)
                ID_SIMPLE_BLOCK -> parseSimpleBlock(c, elSize.value, clusterTimecode)
                else -> c.skip(elSize.value)
            }
        }
    }

    private fun parseSimpleBlock(c: CountingStream, size: Long, clusterTimecode: Long) {
        val blockStart = c.position
        // Element layout: track-number VINT, 2-byte signed relative timecode, 1-byte flags, payload.
        c.skipVint()                                  // track number
        val relTime = c.readSignedShort()             // relative timecode within cluster
        c.skip(1)                                     // flags (keyframe bit etc.)
        val payloadOffset = c.position
        val payloadSize = (size - (c.position - blockStart)).toInt()
        if (payloadSize > 0) {
            frames.add(FrameRef(payloadOffset, payloadSize, clusterTimecode + relTime))
        }
        c.skip(size - (payloadOffset - blockStart))   // skip over the payload
    }

    private class Vint(val value: Long, val isUnknown: Boolean)

    /** InputStream wrapper that tracks absolute byte position and adds EBML primitives. */
    private class CountingStream(private val ins: InputStream) {
        var position: Long = 0L
            private set

        fun close() = ins.close()

        private fun readByte(): Int {
            val b = ins.read()
            if (b >= 0) position++
            return b
        }

        fun skip(n: Long) {
            var remaining = n
            while (remaining > 0) {
                val s = ins.skip(remaining)
                if (s > 0) {
                    position += s
                    remaining -= s
                } else if (readByte() < 0) {
                    return
                } else {
                    remaining--
                }
            }
        }

        /** Reads an EBML element ID (keeps the length marker bits as part of the id). */
        fun readId(): Long? {
            val first = readByte()
            if (first < 0) return null
            var len = 1
            var mask = 0x80
            while (mask > 0 && first and mask == 0) {
                len++
                mask = mask shr 1
            }
            var id = first.toLong()
            repeat(len - 1) { id = (id shl 8) or (readByte().toLong() and 0xFF) }
            return id
        }

        /** Reads an EBML data-size VINT; flags the all-ones value as unknown/ streaming length. */
        fun readSize(): Vint? {
            val first = readByte()
            if (first < 0) return null
            var len = 1
            var mask = 0x80
            while (mask > 0 && first and mask == 0) {
                len++
                mask = mask shr 1
            }
            var value = (first and (0xFF shr len)).toLong() and 0xFF
            repeat(len - 1) { value = (value shl 8) or (readByte().toLong() and 0xFF) }
            val unknown = len < 8 && value == ((1L shl (7 * len)) - 1)
            return Vint(value, unknown)
        }

        /** Reads just enough to consume a VINT (e.g. a block track number), discarding the value. */
        fun skipVint() {
            val first = readByte()
            if (first < 0) return
            var len = 1
            var mask = 0x80
            while (mask > 0 && first and mask == 0) {
                len++
                mask = mask shr 1
            }
            skip((len - 1).toLong())
        }

        fun readUint(size: Long): Long {
            var v = 0L
            repeat(size.toInt().coerceAtMost(8)) { v = (v shl 8) or (readByte().toLong() and 0xFF) }
            return v
        }

        fun readSignedShort(): Long {
            val hi = readByte()
            val lo = readByte()
            if (hi < 0 || lo < 0) return 0L
            return (((hi shl 8) or lo).toShort()).toLong()
        }
    }

    companion object {
        private const val ID_EBML_HEADER = 0x1A45DFA3L
        private const val ID_SEGMENT = 0x18538067L
        private const val ID_CLUSTER = 0x1F43B675L
        private const val ID_TIMECODE = 0xE7L
        private const val ID_SIMPLE_BLOCK = 0xA3L
    }
}
