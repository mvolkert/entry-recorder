package io.github.mvolkert.entryrecorder.video

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import io.github.mvolkert.entryrecorder.data.local.entity.RecordingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Converts an app `V_MJPEG` MKV recording into a hardware-decodable H.264 fragmented MP4.
 *
 * This is intentionally run **only on export/share**, never during capture: it is the single place
 * where the app pays the CPU/battery cost of re-encoding, so the 24/7 monitor stays cheap. The
 * produced file plays in third-party players (WhatsApp, gallery, VLC, browsers, …), unlike the
 * raw MJPEG MKV. fMP4 is the encoded deliverable's container by design: H.264's most universally
 * supported home, while Matroska remains the private crash-resilient capture buffer.
 */
object ExportTranscoder {
    private const val TAG = "ExportTranscoder"

    // Bound the transcode resolution so export CPU/heat stays predictable on phones.
    private const val MAX_DIM = 1280
    private const val MIN_DIM = 16

    /**
     * Transcodes [recording]'s MKV to a temporary H.264 fMP4 under the app cache dir.
     * @return the produced file.
     * @throws IllegalStateException if there are no frames or the platform encoder is unavailable.
     */
    suspend fun transcodeToH264(
        context: Context,
        recording: RecordingEntity,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val source = File(recording.filePath)
        val reader = MjpegMkvReader(source)
        val refs = reader.readFrameIndex()
        check(refs.isNotEmpty()) { "Recording has no decodable frames" }

        val (w, h) = targetSize(runCatching { reader.readFrame(refs.first()) }.getOrDefault(ByteArray(0)))
        val fps = estimateFps(refs)
        val bitrate = estimateBitrate(w, h, fps)

        val outDir = File(context.cacheDir, "export").apply { mkdirs() }
        val outFile = File(outDir, "${source.nameWithoutExtension}_h264.mp4")

        Log.i(TAG, "Transcoding ${refs.size} frames ${w}x$h @ ${fps}fps -> ${outFile.name}")

        H264Encoder(w, h, fps, bitrate).use { encoder ->
            encoder.start()
            Fmp4StreamMuxer(outFile, fps = fps).use { muxer ->
                var configured = false
                val firstTs = refs.first().timestampMs

                fun emit(frames: List<H264Encoder.EncodedFrame>) {
                    for (f in frames) {
                        if (!configured) {
                            val cp = encoder.codecPrivate ?: continue
                            if (!f.isKeyframe) continue
                            muxer.configureH264(cp, w, h)
                            configured = true
                        }
                        muxer.writeH264Frame(f.annexB, f.presentationTimeUs / 1000, f.isKeyframe)
                    }
                }

                for ((i, ref) in refs.withIndex()) {
                    coroutineContext.ensureActive()
                    val jpeg = reader.readFrame(ref)
                    val ptsUs = (ref.timestampMs - firstTs).coerceAtLeast(0L) * 1000L
                    emit(encoder.encode(jpeg, ptsUs))
                    onProgress(i + 1, refs.size)
                }
                emit(encoder.drain())

                check(configured) { "Encoder produced no H.264 keyframe" }
            }
        }
        outFile
    }

    private fun targetSize(firstJpeg: ByteArray): Pair<Int, Int> {
        var w = 1280
        var h = 720
        if (firstJpeg.isNotEmpty()) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(firstJpeg, 0, firstJpeg.size, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                w = opts.outWidth
                h = opts.outHeight
            }
        }
        val scale = minOf(1f, MAX_DIM.toFloat() / maxOf(w, h))
        w = (w * scale).toInt().coerceAtLeast(MIN_DIM)
        h = (h * scale).toInt().coerceAtLeast(MIN_DIM)
        // H.264 requires even dimensions.
        w -= (w % 2)
        h -= (h % 2)
        return w to h
    }

    private fun estimateFps(refs: List<MjpegMkvReader.FrameRef>): Int {
        if (refs.size < 2) return 5
        val spanMs = refs.last().timestampMs - refs.first().timestampMs
        if (spanMs <= 0) return 5
        return (((refs.size - 1) * 1000.0) / spanMs).toInt().coerceIn(1, 30)
    }

    private fun estimateBitrate(w: Int, h: Int, fps: Int): Int {
        val target = (w * h * fps * 0.07).toInt()
        return target.coerceIn(300_000, 6_000_000)
    }
}
