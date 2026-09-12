package io.github.mvolkert.entryrecorder.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import io.github.mvolkert.entryrecorder.data.local.entity.RecordingEntity
import java.io.File
import java.io.FileInputStream

object ExportHelper {
    private const val TAG = "ExportHelper"

    /**
     * Share video file via Android system share sheet (WhatsApp, Email, Cloud, etc.)
     */
    fun shareRecording(context: Context, recording: RecordingEntity) {
        val file = File(recording.filePath)
        if (!file.exists()) {
            Toast.makeText(context, "Video file not found", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val contentUri: Uri = FileProvider.getUriForFile(
                context,
                "io.github.mvolkert.entryrecorder.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_SUBJECT, "EntryRecorder: ${recording.deviceName} - ${recording.eventType}")
                putExtra(Intent.EXTRA_TEXT, "Video recording from ${recording.deviceName} (${recording.eventType})")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(Intent.createChooser(shareIntent, "Export Recording via..."))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share video", e)
            Toast.makeText(context, "Export failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Save video to public Downloads / Movies folder via MediaStore
     */
    fun saveToPublicGallery(context: Context, recording: RecordingEntity) {
        val sourceFile = File(recording.filePath)
        if (!sourceFile.exists()) {
            Toast.makeText(context, "Source file not found", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val fileName = "EntryRecorder_${recording.deviceName}_${System.currentTimeMillis()}.mp4"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/EntryRecorder")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }

                val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val itemUri = context.contentResolver.insert(collection, values)

                if (itemUri != null) {
                    context.contentResolver.openOutputStream(itemUri).use { out ->
                        FileInputStream(sourceFile).use { input ->
                            input.copyTo(out!!)
                        }
                    }

                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    context.contentResolver.update(itemUri, values, null, null)
                    Toast.makeText(context, "Saved to Movies/EntryRecorder", Toast.LENGTH_LONG).show()
                }
            } else {
                val destDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "EntryRecorder")
                destDir.mkdirs()
                val destFile = File(destDir, fileName)
                sourceFile.copyTo(destFile, overwrite = true)
                Toast.makeText(context, "Saved to ${destFile.absolutePath}", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save to gallery", e)
            Toast.makeText(context, "Save failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }
}
