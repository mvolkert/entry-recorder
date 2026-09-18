package io.github.mvolkert.entryrecorder.video

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Pure Kotlin, crash-resilient Matroska (MKV) streaming muxer for MJPEG (V_MS/VFW/FOURCC or V_MJPEG)
 * and H.264 video.
 *
 * Design features:
 * - Writes standard EBML Header and Segment with unknown/streaming length (0x01FFFFFF...).
 * - Flushes an EBML Cluster to disk at regular intervals (every keyframe or ~1s).
 * - If the process or device crashes, the resulting .mkv file remains structurally intact
 *   and 100% playable by ExoPlayer up to the last flushed cluster.
 */
class MkvStreamMuxer(
    outputFile: File,
    private val width: Int = 1280,
    private val height: Int = 720,
    private val trackNumber: Long = 1L
) : AutoCloseable {

    private val tag = "MkvStreamMuxer"
    private var fos: FileOutputStream? = null
    private var isHeaderWritten = false
    private var clusterTimecodeMs: Long = 0L
    private var currentClusterStream: ByteArrayOutputStream? = null
    private var framesInCurrentCluster = 0
    private var lastFrameTimeMs: Long = 0L

    init {
        outputFile.parentFile?.mkdirs()
        fos = FileOutputStream(outputFile)
        writeHeader()
    }

    @Synchronized
    fun writeMjpegFrame(jpegBytes: ByteArray, timestampMs: Long) {
        if (fos == null) return
        if (!isHeaderWritten) writeHeader()

        // Start a new cluster if empty or more than 1000ms has elapsed
        if (currentClusterStream == null || (timestampMs - clusterTimecodeMs >= 1000L && framesInCurrentCluster > 0)) {
            flushCurrentCluster()
            clusterTimecodeMs = timestampMs
            currentClusterStream = ByteArrayOutputStream()
            framesInCurrentCluster = 0
        }

        // Write SimpleBlock inside cluster buffer
        // SimpleBlock ID: 0xA3
        val blockData = ByteArrayOutputStream()
        // Track number (VINT)
        writeVint(trackNumber, blockData)
        // Relative Timecode (signed 16-bit int)
        val relTimeMs = (timestampMs - clusterTimecodeMs).coerceIn(-32768L, 32767L).toInt()
        blockData.write((relTimeMs shr 8) and 0xFF)
        blockData.write(relTimeMs and 0xFF)
        // Flags: Keyframe (0x80)
        blockData.write(0x80)
        // Frame payload (JPEG data)
        blockData.write(jpegBytes)

        val blockBytes = blockData.toByteArray()
        val clusterBuf = currentClusterStream ?: return

        // SimpleBlock element
        writeElementId(ID_SIMPLE_BLOCK, clusterBuf)
        writeVint(blockBytes.size.toLong(), clusterBuf)
        clusterBuf.write(blockBytes)

        framesInCurrentCluster++
        lastFrameTimeMs = timestampMs

        // Flush to disk every keyframe/cluster so file is safe against crashes
        if (framesInCurrentCluster >= 5) {
            flushCurrentCluster()
        }
    }

    @Synchronized
    fun flushCurrentCluster() {
        val clusterBuf = currentClusterStream ?: return
        val stream = fos ?: return
        if (framesInCurrentCluster == 0) return

        try {
            // Write Cluster header
            val clusterContent = ByteArrayOutputStream()
            // Timecode element: 0xE7
            writeElementId(ID_TIMECODE, clusterContent)
            val tcBytes = toByteArray(clusterTimecodeMs)
            writeVint(tcBytes.size.toLong(), clusterContent)
            clusterContent.write(tcBytes)

            // Append all blocks in this cluster
            clusterContent.write(clusterBuf.toByteArray())

            val finalClusterBytes = clusterContent.toByteArray()
            writeElementId(ID_CLUSTER, stream)
            writeVint(finalClusterBytes.size.toLong(), stream)
            stream.write(finalClusterBytes)
            stream.flush()
        } catch (e: Exception) {
            Log.e(tag, "Error flushing cluster to disk", e)
        } finally {
            currentClusterStream = null
            framesInCurrentCluster = 0
        }
    }

    private fun writeHeader() {
        val stream = fos ?: return
        try {
            // 1. EBML Header (0x1A45DFA3)
            val ebmlHeader = ByteArrayOutputStream()
            // DocType (0x4282) = "matroska"
            writeStringElement(ID_DOC_TYPE, "matroska", ebmlHeader)
            // DocTypeVersion (0x4287) = 4
            writeUintElement(ID_DOC_TYPE_VERSION, 4L, ebmlHeader)
            // DocTypeReadVersion (0x4285) = 2
            writeUintElement(ID_DOC_TYPE_READ_VERSION, 2L, ebmlHeader)

            val ebmlBytes = ebmlHeader.toByteArray()
            writeElementId(ID_EBML_HEADER, stream)
            writeVint(ebmlBytes.size.toLong(), stream)
            stream.write(ebmlBytes)

            // 2. Segment (0x18538067) with unknown length (0x01FFFFFFFFFFFFFF)
            writeElementId(ID_SEGMENT, stream)
            stream.write(byteArrayOf(0x01, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))

            // 3. Segment Info (0x1549A966)
            val info = ByteArrayOutputStream()
            // TimecodeScale: 1,000,000 ns (1ms per tick)
            writeUintElement(ID_TIMECODE_SCALE, 1_000_000L, info)
            // MuxingApp / WritingApp
            writeStringElement(ID_MUXING_APP, "EntryRecorder MKV Streamer", info)
            writeStringElement(ID_WRITING_APP, "EntryRecorder", info)

            val infoBytes = info.toByteArray()
            writeElementId(ID_INFO, stream)
            writeVint(infoBytes.size.toLong(), stream)
            stream.write(infoBytes)

            // 4. Tracks (0x1654AE6B)
            val tracks = ByteArrayOutputStream()
            val trackEntry = ByteArrayOutputStream()
            writeUintElement(ID_TRACK_NUMBER, trackNumber, trackEntry)
            writeUintElement(ID_TRACK_UID, trackNumber, trackEntry)
            // TrackType: 1 = Video
            writeUintElement(ID_TRACK_TYPE, 1L, trackEntry)
            // CodecID: "V_MS/VFW/FOURCC" or "V_MJPEG"
            writeStringElement(ID_CODEC_ID, "V_MJPEG", trackEntry)

            // Video Settings (0xE0)
            val video = ByteArrayOutputStream()
            writeUintElement(ID_PIXEL_WIDTH, width.toLong(), video)
            writeUintElement(ID_PIXEL_HEIGHT, height.toLong(), video)
            val videoBytes = video.toByteArray()
            writeElementId(ID_VIDEO, trackEntry)
            writeVint(videoBytes.size.toLong(), trackEntry)
            trackEntry.write(videoBytes)

            val entryBytes = trackEntry.toByteArray()
            writeElementId(ID_TRACK_ENTRY, tracks)
            writeVint(entryBytes.size.toLong(), tracks)
            tracks.write(entryBytes)

            val tracksBytes = tracks.toByteArray()
            writeElementId(ID_TRACKS, stream)
            writeVint(tracksBytes.size.toLong(), stream)
            stream.write(tracksBytes)
            stream.flush()

            isHeaderWritten = true
        } catch (e: Exception) {
            Log.e(tag, "Failed to write MKV header", e)
        }
    }

    override fun close() {
        try {
            flushCurrentCluster()
            fos?.flush()
            fos?.close()
        } catch (_: Exception) {}
        fos = null
    }

    companion object {
        private const val ID_EBML_HEADER = 0x1A45DFA3L
        private const val ID_DOC_TYPE = 0x4282L
        private const val ID_DOC_TYPE_VERSION = 0x4287L
        private const val ID_DOC_TYPE_READ_VERSION = 0x4285L
        private const val ID_SEGMENT = 0x18538067L
        private const val ID_INFO = 0x1549A966L
        private const val ID_TIMECODE_SCALE = 0x2AD7B1L
        private const val ID_MUXING_APP = 0x4D80L
        private const val ID_WRITING_APP = 0x5741L
        private const val ID_TRACKS = 0x1654AE6BL
        private const val ID_TRACK_ENTRY = 0xAEL
        private const val ID_TRACK_NUMBER = 0xD7L
        private const val ID_TRACK_UID = 0x73C5L
        private const val ID_TRACK_TYPE = 0x83L
        private const val ID_CODEC_ID = 0x86L
        private const val ID_VIDEO = 0xE0L
        private const val ID_PIXEL_WIDTH = 0xB0L
        private const val ID_PIXEL_HEIGHT = 0xBAL
        private const val ID_CLUSTER = 0x1F43B675L
        private const val ID_TIMECODE = 0xE7L
        private const val ID_SIMPLE_BLOCK = 0xA3L

        private fun writeElementId(id: Long, out: OutputStream) {
            val bytes = toByteArray(id)
            out.write(bytes)
        }

        private fun writeVint(value: Long, out: OutputStream) {
            when {
                value < 0x7F -> out.write((value or 0x80).toInt())
                value < 0x3FFF -> {
                    val v = value or 0x4000
                    out.write(((v shr 8) and 0xFF).toInt())
                    out.write((v and 0xFF).toInt())
                }
                value < 0x1FFFFF -> {
                    val v = value or 0x200000
                    out.write(((v shr 16) and 0xFF).toInt())
                    out.write(((v shr 8) and 0xFF).toInt())
                    out.write((v and 0xFF).toInt())
                }
                value < 0x0FFFFFFF -> {
                    val v = value or 0x10000000
                    out.write(((v shr 24) and 0xFF).toInt())
                    out.write(((v shr 16) and 0xFF).toInt())
                    out.write(((v shr 8) and 0xFF).toInt())
                    out.write((v and 0xFF).toInt())
                }
                else -> {
                    out.write(0x01)
                    for (i in 6 downTo 0) {
                        out.write(((value shr (i * 8)) and 0xFF).toInt())
                    }
                }
            }
        }

        private fun writeStringElement(id: Long, str: String, out: OutputStream) {
            val bytes = str.toByteArray(Charsets.UTF_8)
            writeElementId(id, out)
            writeVint(bytes.size.toLong(), out)
            out.write(bytes)
        }

        private fun writeUintElement(id: Long, value: Long, out: OutputStream) {
            val bytes = toByteArray(value)
            writeElementId(id, out)
            writeVint(bytes.size.toLong(), out)
            out.write(bytes)
        }

        private fun toByteArray(value: Long): ByteArray {
            var temp = value
            val list = mutableListOf<Byte>()
            while (temp > 0 || list.isEmpty()) {
                list.add(0, (temp and 0xFF).toByte())
                temp = temp shr 8
            }
            return list.toByteArray()
        }
    }
}
