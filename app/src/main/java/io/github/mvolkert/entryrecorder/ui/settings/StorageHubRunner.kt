package io.github.mvolkert.entryrecorder.ui.settings

import android.net.Uri
import timber.log.Timber
import io.github.mvolkert.entryrecorder.di.IoDispatcher
import io.github.mvolkert.entryrecorder.util.AppStorageDirs
import io.github.mvolkert.entryrecorder.util.ExportEntry
import io.github.mvolkert.entryrecorder.util.ExportFolderAccess
import io.github.mvolkert.entryrecorder.util.ExportGateway
import io.github.mvolkert.entryrecorder.util.thumbnailUsageBytes
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * The blocking work behind the storage hub: the folder-grant probe, the two usage measurements, the folder
 * label, and the persistable-grant pair the folder picker needs. Each returns plain data, so the ViewModel
 * keeps the flow pipelines and their `flowOn(…)` / `stateIn(…)` while the provider and filesystem round
 * trips live here — and a JVM test can drive every rule below with a fake gateway.
 *
 * The four probe methods are deliberately not `suspend`: each is only ever called from inside a flow that
 * already runs on the IO dispatcher. The grant pair *is* `suspend`, because those arrive straight from a
 * click in the ViewModel's own coroutine.
 */
class StorageHubRunner @Inject constructor(
    private val exportGateway: ExportGateway,
    private val storageDirs: AppStorageDirs,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val tag = "StorageHubRunner"

    /** Whether the stored export folder is still writable: a grant the user revoked, a folder they deleted, or a URI that only came back from a backup file. */
    fun probeFolderAccess(folderUri: String): ExportFolderAccess =
        exportGateway.probeExportFolderAccess(folderUri)

    /** Bytes the thumbnail cache holds in the app's private storage. */
    fun thumbnailCacheBytes(): Long = thumbnailUsageBytes(storageDirs.thumbnailsDir)

    /**
     * Bytes the app created inside the export folder, or null when the provider refused the listing.
     *
     * The query is a third-party `ContentProvider`, so it fails as readily as it succeeds, and its caller
     * sits in a `stateIn(viewModelScope…)`: an exception escaping here is uncaught and kills the screen while
     * it is being opened. A folder that cannot be read is reported as no number, never as a crash.
     */
    fun exportedBytes(folderUri: String): Long? = runCatching {
        exportGateway.listExportedFiles(folderUri)
            .filter(ExportEntry::isAppCreated)
            .sumOf { it.sizeBytes }
    }.onFailure {
        if (it is CancellationException) throw it
        Timber.tag(tag).w(it, "Export folder listing for $folderUri failed")
    }.getOrNull()

    /**
     * The name the document provider gives the chosen export folder, "" when it refuses the query. It is a
     * number-free label, so a refused query reads as no name rather than as a broken folder.
     */
    fun folderDisplayName(folderUri: String): String = runCatching {
        exportGateway.safFolderDisplayName(folderUri)
    }.onFailure {
        if (it is CancellationException) throw it
        Timber.tag(tag).w(it, "Export folder name for $folderUri could not be read")
    }.getOrDefault("")

    /** Claims the persistable permission for a freshly picked tree. False when the provider refuses it. */
    suspend fun takeGrant(uri: Uri): Boolean = withContext(ioDispatcher) {
        exportGateway.takePersistableTreeGrant(uri)
    }

    /** Drops the persistable grant so the system's grant list does not pile up across folders the user no longer uses. */
    suspend fun releaseGrant(folderUri: String) = withContext(ioDispatcher) {
        exportGateway.releasePersistableTreeGrant(folderUri)
    }
}
