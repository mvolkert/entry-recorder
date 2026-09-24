package io.github.mvolkert.entryrecorder.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import androidx.core.graphics.scale

object ThumbnailUtil {
    private const val TAG = "ThumbnailUtil"

    /**
     * Builds a thumbnail directly from a captured JPEG snapshot frame, without relying on a
     * video decoder. This is robust for MJPEG/snapshot recordings, whose MKV `V_MJPEG` track
     * cannot be decoded by [MediaMetadataRetriever] on most Android devices.
     */
    fun saveThumbnailFromJpeg(context: Context, jpegBytes: ByteArray, deviceId: Long): String? {
        return try {
            val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return null
            // Downscale to a stable thumbnail width to keep disk usage small.
            val maxDim = 480
            val scaled = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                val ratio = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
                val w = (bitmap.width * ratio).toInt().coerceAtLeast(1)
                val h = (bitmap.height * ratio).toInt().coerceAtLeast(1)
                bitmap.scale(w, h)
            } else bitmap

            val thumbsDir = File(context.filesDir, "thumbnails").apply { mkdirs() }
            val thumbFile = File(thumbsDir, "thumb_${deviceId}_${System.currentTimeMillis()}.jpg")
            FileOutputStream(thumbFile).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            thumbFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save JPEG thumbnail for device $deviceId", e)
            null
        }
    }

    fun extractAndSaveThumbnail(context: Context, videoPath: String, recordingId: Long): String? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoPath)
            // Extract frame around 1 second in, at 0, or at first keyframe
            val bitmap = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
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
