package io.github.mvolkert.entryrecorder.util

import android.content.Context
import io.github.mvolkert.entryrecorder.data.local.entity.RecordingEntity
import java.io.File

/**
 * Live state of the persisted SAF grant behind a stored export-folder URI, so the UI can say *why*
 * exports stopped working instead of only failing at write time.
 */
enum class ExportFolderAccess {
    /** Not measured yet. */
    UNKNOWN,

    /** No folder chosen. */
    NONE,

    /** Grant held and the tree still resolves. */
    OK,

    /** A URI is stored but the persistable permission is gone (revoked, released, or restored from backup). */
    NO_PERMISSION,

    /** Grant held but the document provider no longer serves the tree (folder deleted, provider removed). */
    MISSING,
}

/**
 * Every way a finished clip can leave the app: the system share sheet, the public Movies folder, and the
 * user's own SAF export folder.
 *
 * An interface rather than the concrete class because three of its methods decide what gets *deleted* from
 * the export folder, and the retention policy behind them has to be exercisable without a live
 * ContentProvider — see [CleanupRunner]. The folder itself is addressed by its stored tree-URI string, never
 * by a parsed `Uri` or a document `Uri`, so nothing outside this type has to touch the Android runtime to
 * talk to it; the implementation resolves both.
 *
 * [ExportFolderAccess] says how good a stored folder currently is; the caller decides whether to warn.
 */
interface ExportGateway {

    /**
     * Shares several recordings at once via the system share sheet (multi-select export). Nothing here
     * toasts: this is not UI, so whatever the user needs to hear goes back to the caller through
     * [onMessage]. [context] has to be an Activity — the batch sheet adds no NEW_TASK on its own.
     */
    fun shareFiles(context: Context, files: List<File>, onMessage: (String) -> Unit = {})

    /** Shares a single video [file] (e.g. a transcoded H.264 export) labelled by [recording]. */
    fun shareFile(
        context: Context,
        file: File,
        recording: RecordingEntity,
        onMessage: (String) -> Unit = {},
    )

    /**
     * Shares a single video [file] using an explicit [deviceName] / [eventTypeLabel] for the sheet's
     * subject and text. Server recordings have no local row, so this is the form a just-downloaded clip uses.
     */
    fun shareFile(
        context: Context,
        file: File,
        deviceName: String,
        eventTypeLabel: String,
        onMessage: (String) -> Unit = {},
    )

    /**
     * Saves [sourceFile] into the public Movies/EntryRecorder folder, labelled with [recording]'s device
     * name. @return true when the clip is in the gallery afterwards; every outcome text goes to [onMessage].
     */
    fun saveFileToGallery(
        sourceFile: File,
        recording: RecordingEntity,
        onMessage: (String) -> Unit = {},
    ): Boolean

    /** [saveFileToGallery] with an explicit [deviceName] used only for the file label. */
    fun saveFileToGallery(
        sourceFile: File,
        deviceName: String,
        onMessage: (String) -> Unit = {},
    ): Boolean

    /**
     * Copies [sourceFile] into the SAF folder [folderUri] under [displayName]. A document that already
     * exists with the same name and the same byte size is left untouched and reported as success; a size
     * mismatch (stale or interrupted copy) replaces it. [onProgress] receives 0..100 of bytes copied and is
     * invoked from the caller's (IO) thread.
     * @return true when the folder holds the file after the call, whether copied or already present.
     */
    fun saveFileToSafFolder(
        folderUri: String,
        sourceFile: File,
        displayName: String,
        onProgress: ((Int) -> Unit)? = null,
    ): Boolean

    /**
     * Whether the stored export folder [folderUri] still carries a readable *and* writable persistable
     * permission and resolves at all. Blocking provider work: call it on an IO dispatcher. Any provider
     * failure is reported as [ExportFolderAccess.MISSING] rather than thrown, because the caller can only
     * act on the answer, not fix the folder.
     */
    fun probeExportFolderAccess(folderUri: String): ExportFolderAccess

    /** Human-readable label for the stored export folder (the path after the volume, e.g. "Documents/Exports"). */
    fun safFolderDisplayName(folderUri: String): String

    /**
     * The direct children of the export folder as the cleanup planner sees them: name, size, provider
     * timestamp. Foreign files are included, so the caller decides what counts; subfolders are not. Blocking
     * provider query: call it on an IO dispatcher.
     */
    fun listExportedFiles(folderUri: String): List<ExportEntry>

    /**
     * Removes the direct child named [displayName] from the export folder. Providers are free to refuse
     * deletion (read-only mounts, documents they no longer serve), so the answer is a flag the caller logs
     * rather than an exception it would only swallow.
     */
    fun deleteExportedFile(folderUri: String, displayName: String): Boolean
}
