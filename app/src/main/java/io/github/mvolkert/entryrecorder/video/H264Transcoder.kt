package io.github.mvolkert.entryrecorder.video

import io.github.mvolkert.entryrecorder.domain.model.Recording
import java.io.File

/**
 * The H.264 export encode as a JVM-only contract: [ExportTranscoder] is the single implementation, but
 * the Recordings ViewModel and the export worker code against this interface so their export paths —
 * including the cancelled-run cleanup that needs [outputFor] — can be driven from a unit test without
 * a MediaCodec or a device.
 *
 * Default values for the optional arguments live here, not on the implementation.
 */
interface H264Transcoder {

    /** The H.264 file [source] transcodes to: beside it in the scratch folder, with a `_h264` suffix. */
    fun outputFor(source: File): File

    /**
     * Transcodes [recording]'s MKV to a temporary H.264 fMP4 under the app cache dir.
     * @return the produced file.
     * @throws IllegalStateException if there are no frames or the platform encoder is unavailable.
     */
    suspend fun transcodeToH264(
        recording: Recording,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): File
}
