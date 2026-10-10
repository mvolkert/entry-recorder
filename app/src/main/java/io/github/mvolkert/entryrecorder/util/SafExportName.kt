package io.github.mvolkert.entryrecorder.util

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Recognizes the files this app writes into the export folder, and reads the moment they were recorded out
 * of their names.
 *
 * Identification has to work from the name alone: the internal cleanup pass deletes the recording rows
 * first, so a later external pass can no longer look anything up in the database for exactly the files it
 * needs to judge. The names are matched as **whole grammars**, not as prefixes, because the Python server
 * writes byte-identical `REC_<device>_<EVENT>_<timestamp>` files and `THUMB_<device>_<millis>` images into
 * its own data directory: a `startsWith` test would hand those files to a delete loop as soon as the export
 * folder overlaps a synced copy of the server archive. Anything that is not one of the two patterns below
 * is neither measured nor touched, which is what makes it safe to point the app at a folder that also holds
 * the user's own files.
 */
object SafExportName {

    /** What `RtspStreamRecorder` writes, plus the `_h264` copy `ExportTranscoder` makes at export time. */
    private val recordingName = Regex("""^REC_\d+_[A-Z]+_\d{8}_\d{6}(?:_h264)?\.[A-Za-z0-9]{1,5}$""")

    /** A server recording this app downloaded into the folder (`ServerRecordingClient` picks mkv or mp4). */
    private val serverDownloadName = Regex("""^server_rec_\d+\.[A-Za-z0-9]{1,5}$""")

    private const val TIMESTAMP_PATTERN = "yyyyMMdd_HHmmss"

    /** The two timestamp segments of a [recordingName], before the optional transcode suffix. */
    private val recordingTimestamp = Regex("_(\\d{8})_(\\d{6})(?:_h264)?$")

    /**
     * Whether the app itself put this file in the folder. The usage number of the export quota is measured
     * over exactly these, so it counts the server downloads too — see [isPrunable] for what may be removed.
     */
    fun isAppCreated(displayName: String): Boolean =
        displayName.matches(recordingName) || displayName.matches(serverDownloadName)

    /**
     * Whether a cleanup pass may delete this file. Server downloads are deliberately excluded: their
     * protection flag lives on the server, which a background pass has no business calling, so the archive
     * copy of a clip the user locked would look like any other file here.
     */
    fun isPrunable(displayName: String): Boolean = displayName.matches(recordingName)

    /**
     * When the recording happened, read from a `REC_` name. Null for every other app file — a server
     * download carries no timestamp in its name — which is the caller's cue to fall back to the document's
     * own modification time.
     */
    fun recordedTimestampMillis(displayName: String): Long? {
        if (!displayName.matches(recordingName)) return null
        val match = recordingTimestamp.find(displayName.substringBeforeLast('.')) ?: return null
        val timestamp = "${match.groupValues[1]}_${match.groupValues[2]}"
        return try {
            // Strict, because a lenient parse would roll 31 February into March and order the whole folder
            // by a date that never happened.
            SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US).apply { isLenient = false }.parse(timestamp)?.time
        } catch (_: ParseException) {
            null
        }
    }
}
