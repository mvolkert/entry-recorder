package io.github.mvolkert.entryrecorder.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object ThumbnailUtil {
    private const val TAG = "ThumbnailUtil"

    fun extractAndSaveThumbnail(context: Context, videoPath: String, recordingId: Long): String? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoPath)
            // Extract frame around 1 second in or at first keyframe
            val bitmap = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime

            if (bitmap != null) {
                val thumbsDir = File(context.filesDir, "thumbnails").apply { mkdirs() }
                val thumbFile = File(thumbsDir, "thumb_${recordingId}_${System.currentTimeMillis()}.jpg")
                FileOutputStream(thumbFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                bitmap.recycle()
                thumbFile.absolutePath
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract thumbnail for $videoPath", e)
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
        }
    }
}
