package io.github.mvolkert.entryrecorder.util

import android.util.Log
import io.github.mvolkert.entryrecorder.domain.model.AppSettings
import io.github.mvolkert.entryrecorder.data.repository.IntercomRepository
import io.github.mvolkert.entryrecorder.data.repository.RecordingDeleteResult
import io.github.mvolkert.entryrecorder.di.IoDispatcher
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * What one cleanup run did, in the only terms its caller can act on.
 *
 * [failedCritical] names the passes whose failure means the archive is still over quota, so the schedule has
 * to run again; [failedBestEffort] names the ones that reported a problem a retry cannot fix — a folder on
 * somebody else's storage, a file the filesystem will not release. WorkManager decides whether to retry from
 * the first list alone, which is why the two are never merged into one count.
 */
data class CleanupReport(
    val failedCritical: List<String> = emptyList(),
    val failedBestEffort: List<String> = emptyList(),
)

/**
 * The archive-space policy the daily schedule enforces: expired clips first, then the two independent
 * quotas, then the files a delete leaked.
 *
 * Extracted from [io.github.mvolkert.entryrecorder.worker.RetentionCleanupWorker] so the deleting rules can
 * be tested as ordinary code: what this class decides is a function of the settings row, the database and a
 * folder listing, none of which needs WorkManager or a ContentProvider to be answered. Each pass is gated by
 * its own switch and runs in its own guard, so a folder the app can no longer reach never stops the phone's
 * own storage from being cleaned — and never makes the schedule retry over it. What a guard is willing to
 * delete is decided by [CleanupPlan], not here.
 */
@Singleton
class CleanupRunner @Inject constructor(
    private val repository: IntercomRepository,
    private val exportGateway: ExportGateway,
    private val storageDirs: AppStorageDirs,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    private companion object {
        private const val TAG = "CleanupRunner"
    }

    suspend fun run(settings: AppSettings): CleanupReport = withContext(ioDispatcher) {
        if (!settings.internalCleanupEnabled && !settings.externalCleanupEnabled) {
            Log.i(TAG, "Both cleanup passes are disabled in settings, skipping.")
            return@withContext CleanupReport()
        }

        Log.i(
            TAG,
            "Running cleanup (retentionDays=${settings.retentionDays}, " +
                "internal=${settings.internalQuotaMb}MB/${settings.internalCleanupEnabled}, " +
                "external=${settings.externalQuotaMb}MB/${settings.externalCleanupEnabled})"
        )

        val failed = mutableListOf<String>()
        val bestEffort = mutableListOf<String>()
        runGuarded("retention", failed::add) { purgeExpired(settings) }
        runGuarded("internal quota", failed::add) { purgeInternal(settings) }
        // The external pass is the one the user has to opt into, and it talks to a network provider whose
        // folder may be offline for reasons nobody can fix by retrying. Its failures are therefore reported
        // but not counted: making them retry would re-run the two passes above once per backoff for a folder
        // that is deliberately somebody else's storage.
        runGuarded("external quota", bestEffort::add) { purgeExternal(settings) }
        // Last, so a pass that just deleted rows cannot have its freshly leaked file swept before it is
        // even counted: the sweep only ever removes files older than one cleanup interval anyway.
        runGuarded("orphan sweep", bestEffort::add) { purgeOrphans() }

        CleanupReport(failedCritical = failed, failedBestEffort = bestEffort)
    }

    /**
     * Runs [block], calling [onFailure] with [name] when it throws. A cancellation always propagates —
     * swallowing it would let the caller believe a pass finished when it was stopped halfway through.
     */
    private suspend fun runGuarded(name: String, onFailure: (String) -> Unit, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "The $name pass failed", e)
            onFailure(name)
        }
    }

    /**
     * Age is day-exact and part of cleaning the phone's own storage, so the internal switch covers it —
     * turning "Auto-Clean Phone Storage" off means nothing on the phone is deleted, quota or not.
     */
    private suspend fun purgeExpired(settings: AppSettings) {
        if (!settings.internalCleanupEnabled) return
        if (settings.retentionDays <= 0) return
        val expired = repository.getExpiredRecordings(settings.retentionDays)
        Log.i(TAG, "Found ${expired.size} expired recordings to delete")
        expired.forEach { logOrphans(repository.deleteRecording(it), "retention") }
    }

    /**
     * Brings the app's private storage back under its quota by deleting unprotected clips, oldest first.
     * The quota is bound over what this pass may actually remove — unprotected clips plus the thumbnails
     * that are deleted together with them — so protected footage and leftover thumbnails can never be paid
     * for with recordings the user did not lock away.
     */
    private suspend fun purgeInternal(settings: AppSettings) {
        if (!settings.internalCleanupEnabled) {
            Log.i(TAG, "Internal cleanup disabled, skipping the quota pass")
            return
        }
        val usage = StorageUsage(
            recordingsBytes = repository.getTotalStorageBytes(),
            thumbnailBytes = thumbnailUsageBytes(storageDirs.thumbnailsDir),
        )
        val candidates = repository.getOldestUnprotectedRecordings().map { rec ->
            InternalCandidate(rec, rec.fileSizeBytes + thumbnailBytes(rec.thumbnailPath))
        }
        val plan = CleanupPlan.internal(candidates, settings.internalQuotaMb)
        if (plan.bytesToFree <= 0L) {
            Log.i(
                TAG,
                "Internal usage ${usage.totalBytes} bytes with ${plan.deletableBytes} deletable of a " +
                    "${settings.internalQuotaMb}MB quota: nothing to free"
            )
            return
        }

        Log.i(TAG, "Internal usage ${usage.totalBytes} bytes is over quota, need to free ${plan.bytesToFree} bytes")
        plan.deletions.forEach { logOrphans(repository.deleteRecording(it.recording), "internal quota") }
        Log.i(
            TAG,
            "Internal pass deleted ${plan.deletions.size} recordings, reclaimed ~${plan.reclaimedBytes} " +
                "of ${plan.bytesToFree} bytes"
        )
    }

    /**
     * A clip's own thumbnail counts towards the internal usage, so it has to count towards the reclaim as
     * well: `deleteRecording` removes both files and a pass that only credited the video would keep
     * deleting past its quota. A missing or already-removed thumbnail reports zero length.
     */
    private fun thumbnailBytes(thumbnailPath: String?): Long = thumbnailPath?.let { File(it).length() } ?: 0L

    /**
     * Brings the SAF export folder back under its own quota. Deliberately narrower than the internal pass:
     * the folder is the archive the user syncs elsewhere, so it only ever loses files whose whole name matches
     * what this app writes, it never touches a subfolder, and a protected clip keeps its mirror copies.
     * Downloads of server recordings count towards the usage but are never pruned here — their protection
     * flag lives on the server, which this pass does not call. The ordering, bound and exclusion all live in
     * [CleanupPlan.external], which is the tested half of this decision.
     */
    private suspend fun purgeExternal(settings: AppSettings) {
        if (!settings.externalCleanupEnabled) return
        val folderUri = settings.exportFolderUri
        if (folderUri.isBlank()) return
        if (exportGateway.probeExportFolderAccess(folderUri) != ExportFolderAccess.OK) {
            Log.i(TAG, "Export folder is not usable right now, skipping the external pass")
            return
        }

        val documents = exportGateway.listExportedFiles(folderUri)
        val plan = CleanupPlan.external(
            entries = documents,
            protectedNames = CleanupPlan.protectedExportNames(repository.getProtectedFilePaths()),
            quotaMb = settings.externalQuotaMb,
        )
        if (plan.bytesToFree <= 0L) {
            val appBytes = documents.filter(ExportEntry::isAppCreated).sumOf { it.sizeBytes }
            Log.i(
                TAG,
                "Export folder holds $appBytes of app files with ${plan.deletableBytes} prunable of a " +
                    "${settings.externalQuotaMb}MB quota: nothing to free"
            )
            return
        }

        var freed = 0L
        var deleted = 0
        var refused = 0
        for (target in plan.deletions) {
            if (exportGateway.deleteExportedFile(folderUri, target.displayName)) {
                freed += target.sizeBytes
                deleted++
            } else {
                // Skip rather than stop: one document a provider will not remove (a read-only mount, a file
                // it no longer serves) must not disable the archive quota from then on, and the alternative —
                // retrying that file every day forever — is what the refusal counter makes visible here.
                refused++
            }
        }
        Log.i(
            TAG,
            "External pass deleted $deleted of ${plan.deletions.size} planned exported files, " +
                "reclaimed ~$freed of ${plan.bytesToFree} bytes" +
                if (refused > 0) " ($refused refused by the provider)" else ""
        )
        if (freed < plan.bytesToFree) {
            // The plan itself cannot come up short: its target is measured over the very files it picked.
            // So whatever is missing was refused or moved, and saying so is the only signal a user gets that
            // the folder will keep growing despite the switch being on.
            Log.w(
                TAG,
                "Export folder quota not met: the provider removed less than the plan asked for " +
                    "(${plan.bytesToFree - freed} bytes short)"
            )
        }
    }

    /**
     * Reclaims the bytes a delete leaked: a file that outlived its row is counted by no quota anymore, so
     * without this pass the leak is permanent and invisible. Restricted to files no row references and
     * untouched for a whole cleanup interval, because a recording that is still being written has no row yet.
     *
     * Best-effort by construction — it can only ever remove unreferenced files, so a failure here is reported
     * by the guard around it and never turns into a retry that re-runs the two quota passes above.
     */
    private suspend fun purgeOrphans() {
        val knownPaths = repository.getAllReferencedFilePaths().toSet()
        var removed = 0
        var reclaimedBytes = 0L
        var refused = 0
        for (dir in listOf(storageDirs.recordingsDir, storageDirs.thumbnailsDir)) {
            for (file in CleanupPlan.orphanFiles(dir, knownPaths)) {
                val size = file.length()
                if (file.delete() || !file.exists()) {
                    removed++
                    reclaimedBytes += size
                } else {
                    refused++
                }
            }
        }
        if (removed > 0 || refused > 0) {
            Log.i(
                TAG,
                "Orphan sweep removed $removed unreferenced file(s), reclaimed ~$reclaimedBytes bytes" +
                    if (refused > 0) " ($refused refused by the filesystem)" else ""
            )
        }
    }

    /**
     * A delete whose files survived is the leak [purgeOrphans] exists to close, so it is worth a warn even
     * though the row went: the archive just got smaller than the space it still occupies.
     */
    private fun logOrphans(result: RecordingDeleteResult, pass: String) {
        if (result is RecordingDeleteResult.DeletedWithOrphans) {
            Log.w(
                TAG,
                "The $pass pass deleted the row but left ${result.orphanPaths.size} unreferenced file(s): " +
                    result.orphanPaths.joinToString()
            )
        }
    }
}
