package io.github.mvolkert.entryrecorder.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
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
     * Derives the share/storage MIME type from the actual file extension so MKV recordings are
     * no longer mislabelled as MP4 after the crash-resilient MKV pivot.
     */
    private fun mimeFor(file: File): String = when (file.extension.lowercase()) {
        "mp4" -> "video/mp4"
        "mkv", "m4v" -> "video/x-matroska"
        else -> "video/*"
    }

    /**
     * Share video file via Android system share sheet (WhatsApp, Email, Cloud, etc.)
     */
    fun shareRecording(context: Context, recording: RecordingEntity) {
        shareFile(context, File(recording.filePath), recording)
    }

    /**
     * Shares an arbitrary video [file] (e.g. a transcoded H.264 export) via the system share sheet.
     */
    fun shareFile(context: Context, file: File, recording: RecordingEntity) {
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
                type = mimeFor(file)
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
        saveFileToGallery(context, File(recording.filePath), recording)
    }

    /**
     * Saves an arbitrary video [sourceFile] (e.g. a transcoded H.264 export) to the public
     * Movies/EntryRecorder folder via MediaStore.
     */
    fun saveFileToGallery(context: Context, sourceFile: File, recording: RecordingEntity) {
        if (!sourceFile.exists()) {
            Toast.makeText(context, "Source file not found", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val extension = sourceFile.extension.ifBlank { "mkv" }
            val fileName = "EntryRecorder_${recording.deviceName}_${System.currentTimeMillis()}.$extension"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Video.Media.MIME_TYPE, mimeFor(sourceFile))
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

    /**
     * Copies [sourceFile] into the SAF tree folder [treeUri] (a persisted ACTION_OPEN_DOCUMENT_TREE
     * URI) under [displayName], overwriting an existing document with the same name. Used by
     * "Export to folder" so exports persist to a user-accessible location independent of Share.
     * @return true on success.
     */
    fun saveFileToSafFolder(context: Context, treeUri: Uri, sourceFile: File, displayName: String): Boolean {
        if (!sourceFile.exists()) return false
        return try {
            val existing = findSafChildByName(context, treeUri, displayName)
            if (existing != null) DocumentsContract.deleteDocument(context.contentResolver, existing)

            val parentDoc = DocumentsContract.buildDocumentUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri)
            )
            val child = DocumentsContract.createDocument(
                context.contentResolver, parentDoc, mimeFor(sourceFile), displayName
            ) ?: return false

            context.contentResolver.openOutputStream(child)?.use { out ->
                FileInputStream(sourceFile).use { input -> input.copyTo(out) }
            } ?: return false
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to SAF export folder", e)
            false
        }
    }

    /** Returns the URI of a direct child document named [name] in [treeUri], or null if absent. */
    private fun findSafChildByName(context: Context, treeUri: Uri, name: String): Uri? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (c.moveToNext()) {
                if (c.getString(nameIdx) == name) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(idIdx))
                }
            }
        }
        return null
    }

    /** Human-readable label for a SAF tree URI (the path after the volume, e.g. "Documents/Exports"). */
    fun safFolderDisplayName(treeUri: Uri): String {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val parts = docId.split(":")
            (parts.drop(1).firstOrNull() ?: docId).trimEnd('/')
        } catch (e: Exception) {
            treeUri.toString()
        }
    }
}
