package io.github.mvolkert.entryrecorder.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.mvolkert.entryrecorder.EntryRecorderApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RetentionCleanupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val tag = "RetentionCleanupWorker"

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as EntryRecorderApp
        val repository = app.repository

        try {
            val settings = repository.getSettings()
            if (!settings.autoCleanupEnabled) {
                Log.i(tag, "Auto-cleanup is disabled in settings, skipping.")
                return@withContext Result.success()
            }

            Log.i(tag, "Running retention cleanup worker (retentionDays=${settings.retentionDays}, maxStorage=${settings.maxStorageUsageMb}MB)")

            // 1. Delete recordings older than retentionDays
            if (settings.retentionDays > 0) {
                val expiredRecordings = repository.getExpiredRecordings(settings.retentionDays)
                Log.i(tag, "Found ${expiredRecordings.size} expired recordings to delete")
                for (rec in expiredRecordings) {
                    repository.deleteRecording(rec)
                }
            }

            // 2. Check storage quota limit
            val maxStorageBytes = settings.maxStorageUsageMb * 1024L * 1024L
            var currentStorage = repository.getTotalStorageBytes()

            if (currentStorage > maxStorageBytes) {
                Log.w(tag, "Current storage ($currentStorage bytes) exceeds max ($maxStorageBytes bytes). Purging oldest...")
                val oldestRecordings = repository.getOldestUnprotectedRecordings()
                for (rec in oldestRecordings) {
                    if (currentStorage <= maxStorageBytes) break
                    currentStorage -= rec.fileSizeBytes
                    repository.deleteRecording(rec)
                    Log.d(tag, "Purged recording ${rec.id} (${rec.fileSizeBytes} bytes)")
                }
            }

            Log.i(tag, "Retention cleanup worker finished successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e(tag, "Retention cleanup failed", e)
            Result.retry()
        }
    }
}
