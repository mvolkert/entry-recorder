package io.github.mvolkert.entryrecorder.worker

import android.content.Context
import timber.log.Timber
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.mvolkert.entryrecorder.data.repository.IntercomRepository
import io.github.mvolkert.entryrecorder.util.CleanupRunner
import kotlinx.coroutines.CancellationException

/**
 * The schedule's shell: read the settings row, hand it to [CleanupRunner], and translate what the passes
 * reported into the one answer WorkManager understands.
 *
 * Nothing about *what gets deleted* is decided here, which is the point of the split — that policy lives in
 * [CleanupRunner] and its tests, while this class only owns the retry decision a background worker has to
 * make. A pass that failed for a reason a retry cannot fix (an unreachable export folder) is reported and
 * never turns into a retry.
 */
@HiltWorker
class RetentionCleanupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: IntercomRepository,
    private val cleanupRunner: CleanupRunner,
) : CoroutineWorker(appContext, params) {

    private companion object {
        private const val TAG = "RetentionCleanupWorker"
    }

    override suspend fun doWork(): Result {
        val settings = try {
            repository.getSettings()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Could not read settings for cleanup")
            return Result.retry()
        }

        val report = cleanupRunner.run(settings)
        if (report.failedBestEffort.isNotEmpty()) {
            Timber.tag(TAG).w("Cleanup reported ${report.failedBestEffort.size} best-effort failure(s): " +
                report.failedBestEffort.joinToString())
        }
        return if (report.failedCritical.isEmpty()) {
            Timber.tag(TAG).i("Retention cleanup worker finished successfully")
            Result.success()
        } else {
            // The archive is still over quota, so say when it will be tried again rather than silently
            // letting the daily schedule believe it succeeded.
            Timber.tag(TAG).w(
                "Cleanup finished with ${report.failedCritical.size} failed pass(es) " +
                    "(${report.failedCritical.joinToString()}); retrying later"
            )
            Result.retry()
        }
    }
}
