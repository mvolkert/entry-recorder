package io.github.mvolkert.entryrecorder.util

import android.content.Context
import java.io.File

private const val THUMBNAILS_DIR_NAME = "thumbnails"
private const val RECORDINGS_DIR_NAME = "recordings"

/**
 * What the archive costs the app's private storage. Split by kind because only the recordings half can
 * be reclaimed by deleting rows: a quota that counts thumbnails too needs to say so, otherwise a pass
 * runs forever against space it has no way to free.
 */
data class StorageUsage(
    val recordingsBytes: Long = 0L,
    val thumbnailBytes: Long = 0L,
) {
    val totalBytes: Long get() = recordingsBytes + thumbnailBytes
}

/** The directory in-app thumbnails live in, mirroring where `ThumbnailUtil` writes them. */
fun thumbnailsDirOf(context: Context): File = File(context.filesDir, THUMBNAILS_DIR_NAME)

/** The directory in-app recordings live in, mirroring where `RtspStreamRecorder` writes its MKVs. */
fun recordingsDirOf(context: Context): File = File(context.filesDir, RECORDINGS_DIR_NAME)

/**
 * Total size of the thumbnails on disk. Blocking directory listing, so it belongs on an IO dispatcher;
 * a missing directory simply measures as empty rather than throwing.
 */
fun thumbnailUsageBytes(thumbnailsDir: File): Long =
    thumbnailsDir.listFiles()?.sumOf { it.length() } ?: 0L
