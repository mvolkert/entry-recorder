package io.github.mvolkert.entryrecorder.worker

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.mvolkert.entryrecorder.EntryRecorderApp
import io.github.mvolkert.entryrecorder.data.local.entity.AppSettingsEntity
import io.github.mvolkert.entryrecorder.data.repository.IntercomRepository
import io.github.mvolkert.entryrecorder.util.CleanupPlan
import io.github.mvolkert.entryrecorder.util.ExportFolderAccess
import io.github.mvolkert.entryrecorder.util.ExportHelper
import io.github.mvolkert.entryrecorder.util.InternalCandidate
import io.github.mvolkert.entryrecorder.util.SafExportedFile
import io.github.mvolkert.entryrecorder.util.StorageUsage
import io.github.mvolkert.entryrecorder.util.thumbnailUsageBytes
import io.github.mvolkert.entryrecorder.util.thumbnailsDirOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reclaims archive space on the schedule the settings describe: expired clips first, then the two
 * independent quotas. Each pass is gated by its own switch and runs in its own guard, so a folder the app
 * can no longer reach never stops the phone's own storage from being cleaned — and never makes the daily
 * schedule retry over it. What that guard is willing to delete is decided by [CleanupPlan], not here.
 */
class RetentionCleanupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val tag = "RetentionCleanupWorker"

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as EntryRecorderApp
        val repository = app.repository

        val settings = try {
            repository.getSettings()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(tag, "Could not read settings for cleanup", e)
            return@withContext Result.retry()
        }

        if (!settings.internalCleanupEnabled && !settings.externalCleanupEnabled) {
            Log.i(tag, "Both cleanup passes are disabled in settings, skipping.")
            return@withContext Result.success()
        }

        Log.i(
            tag,
            "Running cleanup worker (retentionDays=${settings.retentionDays}, " +
                "internal=${settings.internalQuotaMb}MB/${settings.internalCleanupEnabled}, " +
                "external=${settings.externalQuotaMb}MB/${settings.externalCleanupEnabled})"
        )

        var criticalFailures = 0
        if (!runGuarded("retention") { purgeExpired(repository, settings) }) criticalFailures++
        if (!runGuarded("internal quota") { purgeInternal(repository, settings) }) criticalFailures++
        // The external pass is the one the user has to opt into, and it talks to a network provider whose
        // folder may be offline for reasons nobody can fix by retrying. Its failures are therefore reported
        // but not counted: making them retry would re-run the two passes above once per backoff for a folder
        // that is deliberately somebody else's storage.
        runGuarded("external quota") { purgeExternal(repository, settings) }

        if (criticalFailures > 0) {
            Log.w(tag, "Cleanup finished with $criticalFailures failed pass(es); retrying later")
            Result.retry()
        } else {
            Log.i(tag, "Retention cleanup worker finished successfully")
            Result.success()
        }
    }

    /**
     * Runs [block], reporting whether it completed. A cancellation always propagates — swallowing it
     * would let WorkManager believe a pass finished when it was stopped halfway through.
     */
    private suspend fun runGuarded(name: String, block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(tag, "The $name pass failed", e)
        false
    }

    /**
     * Age is day-exact and part of cleaning the phone's own storage, so the internal switch covers it —
     * turning "Auto-Clean Phone Storage" off means nothing on the phone is deleted, quota or not.
     */
    private suspend fun purgeExpired(repository: IntercomRepository, settings: AppSettingsEntity) {
        if (!settings.internalCleanupEnabled) return
        if (settings.retentionDays <= 0) return
        val expired = repository.getExpiredRecordings(settings.retentionDays)
        Log.i(tag, "Found ${expired.size} expired recordings to delete")
        expired.forEach { repository.deleteRecording(it) }
    }

    /**
     * Brings the app's private storage back under its quota by deleting unprotected clips, oldest first.
     * The quota is bound over what this pass may actually remove — unprotected clips plus the thumbnails
     * that are deleted together with them — so protected footage and leftover thumbnails can never be paid
     * for with recordings the user did not lock away.
     */
    private suspend fun purgeInternal(repository: IntercomRepository, settings: AppSettingsEntity) {
        if (!settings.internalCleanupEnabled) {
            Log.i(tag, "Internal cleanup disabled, skipping the quota pass")
            return
        }
        val usage = StorageUsage(
            recordingsBytes = repository.getTotalStorageBytes(),
            thumbnailBytes = thumbnailUsageBytes(thumbnailsDirOf(applicationContext)),
        )
        val candidates = repository.getOldestUnprotectedRecordings().map { rec ->
            InternalCandidate(rec, rec.fileSizeBytes + thumbnailBytes(rec.thumbnailPath))
        }
        val plan = CleanupPlan.internal(candidates, settings.internalQuotaMb)
        if (plan.bytesToFree <= 0L) {
            Log.i(
                tag,
                "Internal usage ${usage.totalBytes} bytes with ${plan.deletableBytes} deletable of a " +
                    "${settings.internalQuotaMb}MB quota: nothing to free"
            )
            return
        }

        Log.i(
            tag,
            "Internal usage ${usage.totalBytes} bytes is over quota, need to free ${plan.bytesToFree} bytes"
        )
        plan.deletions.forEach { repository.deleteRecording(it.recording) }
        Log.i(
            tag,
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
    private suspend fun purgeExternal(repository: IntercomRepository, settings: AppSettingsEntity) {
        if (!settings.externalCleanupEnabled) return
        val folderUri = settings.exportFolderUri
        if (folderUri.isBlank()) return
        if (ExportHelper.probeExportFolderAccess(applicationContext, folderUri) != ExportFolderAccess.OK) {
            Log.i(tag, "Export folder is not usable right now, skipping the external pass")
            return
        }

        val documents = ExportHelper.listSafExportedFiles(applicationContext, folderUri.toUri())
        val protectedNames = CleanupPlan.protectedExportNames(repository.getProtectedFilePaths())
        val plan = CleanupPlan.external(
            entries = documents.map(SafExportedFile::toCleanupEntry),
            protectedNames = protectedNames,
            quotaMb = settings.externalQuotaMb,
        )
        if (plan.bytesToFree <= 0L) {
            val appBytes = documents.filter(SafExportedFile::isAppCreated).sumOf { it.sizeBytes }
            Log.i(
                tag,
                "Export folder holds $appBytes of app files with ${plan.deletableBytes} prunable of a " +
                    "${settings.externalQuotaMb}MB quota: nothing to free"
            )
            return
        }

        // Display names are unique inside one folder — a provider renames a collision rather than storing two
        // documents under one name — so the plan's entries can be addressed back to their URIs by name.
        val uriByName = documents.associate { it.displayName to it.uri }
        var freed = 0L
        var deleted = 0
        var refused = 0
        for (target in plan.deletions) {
            val documentUri = uriByName[target.displayName] ?: continue
            if (ExportHelper.deleteSafDocument(applicationContext, documentUri)) {
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
            tag,
            "External pass deleted $deleted of ${plan.deletions.size} planned exported files, " +
                "reclaimed ~$freed of ${plan.bytesToFree} bytes" +
                if (refused > 0) " ($refused refused by the provider)" else ""
        )
        if (freed < plan.bytesToFree) {
            // The plan itself cannot come up short: its target is measured over the very files it picked.
            // So whatever is missing was refused or moved, and saying so is the only signal a user gets that
            // the folder will keep growing despite the switch being on.
            Log.w(
                tag,
                "Export folder quota not met: the provider removed less than the plan asked for " +
                    "(${plan.bytesToFree - freed} bytes short)"
            )
        }
    }
}
