package io.github.mvolkert.entryrecorder.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.data.local.entity.RecordingEntity
import io.github.mvolkert.entryrecorder.ui.theme.eventTypeLabelRes
import java.io.File
import java.io.FileInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The device-backed half of [ExportGateway]: MediaStore, the SAF document provider and the share sheet.
 *
 * Holds the application [context] because everything it does is expressed through ContentResolver calls, and
 * takes an explicit `Context` only on the two share methods — a share sheet has to be started from the
 * Activity that asked for it, since this one adds no NEW_TASK flag.
 */
@Singleton
class AndroidExportGateway @Inject constructor(
    @ApplicationContext private val context: Context,
) : ExportGateway {

    private companion object {
        private const val TAG = "ExportGateway"
    }

    /**
     * Derives the share/storage MIME type from the actual file extension: MKV stays correctly
     * labelled for the raw MJPEG capture files, MP4 (incl. the exported H.264 fMP4) is `video/mp4`.
     */
    private fun mimeFor(file: File): String = when (file.extension.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        else -> "video/*"
    }

    override fun shareFiles(context: Context, files: List<File>, onMessage: (String) -> Unit) {
        val existing = files.filter { it.exists() }
        if (existing.isEmpty()) {
            onMessage(context.getString(R.string.export_share_no_files))
            return
        }
        try {
            val uris = ArrayList<Uri>()
            for (f in existing) {
                uris.add(FileProvider.getUriForFile(context, context.getString(R.string.fileprovider_authority), f))
            }
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "video/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.export_share_chooser, uris.size)).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share files", e)
            onMessage(context.getString(R.string.export_share_failed))
        }
    }

    override fun shareFile(
        context: Context,
        file: File,
        recording: RecordingEntity,
        onMessage: (String) -> Unit,
    ) = shareFile(
        context,
        file,
        recording.deviceName,
        context.getString(eventTypeLabelRes(recording.eventType)),
        onMessage,
    )

    override fun shareFile(
        context: Context,
        file: File,
        deviceName: String,
        eventTypeLabel: String,
        onMessage: (String) -> Unit,
    ) {
        if (!file.exists()) {
            onMessage(context.getString(R.string.export_video_not_found))
            return
        }

        try {
            val contentUri: Uri = FileProvider.getUriForFile(
                context,
                context.getString(R.string.fileprovider_authority),
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeFor(file)
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(
                    Intent.EXTRA_SUBJECT,
                    context.getString(R.string.export_subject_label, deviceName, eventTypeLabel)
                )
                putExtra(
                    Intent.EXTRA_TEXT,
                    context.getString(R.string.export_text_label, deviceName, eventTypeLabel)
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.export_chooser_title)))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share video", e)
            onMessage(context.getString(R.string.recordings_toast_export_failed, e.localizedMessage ?: ""))
        }
    }

    override fun saveFileToGallery(
        sourceFile: File,
        recording: RecordingEntity,
        onMessage: (String) -> Unit,
    ): Boolean = saveFileToGallery(sourceFile, recording.deviceName, onMessage)

    override fun saveFileToGallery(
        sourceFile: File,
        deviceName: String,
        onMessage: (String) -> Unit,
    ): Boolean {
        if (!sourceFile.exists()) {
            onMessage(context.getString(R.string.export_source_not_found))
            return false
        }

        val extension = sourceFile.extension.ifBlank { "mkv" }
        val fileName = "EntryRecorder_${deviceName}_${System.currentTimeMillis()}.$extension"
        val mimeType = mimeFor(sourceFile)
        val usesPendingRow = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        return try {
            // Only the pre-Q row can be addressed by a path, and the path is what the scanner has to be
            // told about afterwards, so it is computed here rather than inside the branch below.
            @Suppress("DEPRECATION") // Owner-approved legacy branch: pre-Q MediaStore has no RELATIVE_PATH.
            val legacyTarget = if (usesPendingRow) null else File(
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "EntryRecorder"),
                fileName,
            )

            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, mimeType)
                if (usesPendingRow) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/EntryRecorder")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                } else {
                    put(MediaStore.MediaColumns.DATA, legacyTarget?.absolutePath)
                }
            }

            val collection = if (usesPendingRow) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
            val itemUri = context.contentResolver.insert(collection, values)
            if (itemUri == null) {
                onMessage(context.getString(R.string.export_mediastore_failed))
                return false
            }

            val stream = context.contentResolver.openOutputStream(itemUri)
            if (stream == null) {
                // The provider created an empty row it will list as a clip; take it back out.
                context.contentResolver.delete(itemUri, null, null)
                onMessage(context.getString(R.string.export_mediastore_failed))
                return false
            }
            stream.use { out -> FileInputStream(sourceFile).use { input -> input.copyTo(out) } }

            if (usesPendingRow) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                context.contentResolver.update(itemUri, values, null, null)
            } else {
                legacyTarget?.let {
                    MediaScannerConnection.scanFile(context, arrayOf(it.absolutePath), null, null)
                }
            }

            onMessage(context.getString(R.string.export_saved_gallery))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save to gallery", e)
            onMessage(context.getString(R.string.export_save_failed, e.localizedMessage ?: ""))
            false
        }
    }

    override fun saveFileToSafFolder(
        folderUri: String,
        sourceFile: File,
        displayName: String,
        onProgress: ((Int) -> Unit)?,
    ): Boolean {
        if (!sourceFile.exists()) return false
        val treeUri = folderUri.toUri()
        return try {
            val existing = findSafChildByName(treeUri, displayName)
            if (existing != null) {
                val (existingUri, existingSize) = existing
                if (existingSize == sourceFile.length()) return true
                DocumentsContract.deleteDocument(context.contentResolver, existingUri)
            }

            val parentDoc = DocumentsContract.buildDocumentUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri)
            )
            val child = DocumentsContract.createDocument(
                context.contentResolver, parentDoc, mimeFor(sourceFile), displayName
            ) ?: return false

            context.contentResolver.openOutputStream(child)?.use { out ->
                val total = sourceFile.length().coerceAtLeast(1L)
                FileInputStream(sourceFile).use { input ->
                    val buffer = ByteArray(128 * 1024)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        copied += read
                        onProgress?.invoke(((copied * 100) / total).coerceAtMost(100L).toInt())
                    }
                }
            } ?: return false
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to SAF export folder", e)
            false
        }
    }

    override fun probeExportFolderAccess(folderUri: String): ExportFolderAccess {
        if (folderUri.isBlank()) return ExportFolderAccess.NONE
        val treeUri = folderUri.toUri()
        val granted = context.contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isReadPermission && it.isWritePermission
        }
        if (!granted) return ExportFolderAccess.NO_PERMISSION
        return runCatching {
            val documentUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri)
            )
            context.contentResolver.query(
                documentUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
            )?.use { cursor ->
                if (cursor.count > 0) ExportFolderAccess.OK else ExportFolderAccess.MISSING
            } ?: ExportFolderAccess.MISSING
        }.getOrDefault(ExportFolderAccess.MISSING)
    }

    override fun safFolderDisplayName(folderUri: String): String {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(folderUri.toUri())
            val parts = docId.split(":")
            (parts.drop(1).firstOrNull() ?: docId).trimEnd('/')
        } catch (e: Exception) {
            folderUri
        }
    }

    override fun listExportedFiles(folderUri: String): List<ExportEntry> {
        if (folderUri.isBlank()) return emptyList()
        val treeUri = folderUri.toUri()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val files = mutableListOf<ExportEntry>()
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
            val nameIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (c.moveToNext()) {
                val name = c.getString(nameIdx) ?: continue
                // Subfolders are the user's own organisation; this app only ever writes flat.
                if (c.getString(mimeIdx) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                files += ExportEntry(
                    displayName = name,
                    sizeBytes = if (sizeIdx >= 0) c.getLong(sizeIdx) else 0L,
                    lastModifiedMillis = if (modifiedIdx >= 0) c.getLong(modifiedIdx) else 0L,
                )
            }
        }
        return files
    }

    override fun deleteExportedFile(folderUri: String, displayName: String): Boolean {
        if (folderUri.isBlank()) return false
        val documentUri = findSafChildByName(folderUri.toUri(), displayName)?.first
            // The listing and the deletion are two provider round trips apart, so the document may have
            // been moved or renamed in between; that is the same answer as a provider refusing the delete.
            ?: return false
        return try {
            DocumentsContract.deleteDocument(context.contentResolver, documentUri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete $displayName from the export folder", e)
            false
        }
    }

    /** Returns the URI and byte size of a direct child document named [name] in [treeUri], or null if absent. */
    private fun findSafChildByName(treeUri: Uri, name: String): Pair<Uri, Long>? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE
        )
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val sizeIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            while (c.moveToNext()) {
                if (c.getString(nameIdx) == name) {
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(idIdx))
                    return uri to c.getLong(sizeIdx)
                }
            }
        }
        return null
    }
}
