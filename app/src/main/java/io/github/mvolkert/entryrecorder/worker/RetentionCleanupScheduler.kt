package io.github.mvolkert.entryrecorder.worker

import android.content.Context
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Asking for an immediate retention-cleanup pass, as a JVM-only contract: the settings ViewModel triggers
 * one from a button but must not hold a `Context` (WorkManager needs one) to do it.
 */
interface RetentionCleanupScheduler {

    /** Enqueues a one-off [RetentionCleanupWorker] run; the worker itself decides what to reclaim. */
    fun runNow()
}

/** The device-backed implementation: WorkManager through the application context. */
class AndroidRetentionCleanupScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : RetentionCleanupScheduler {
    override fun runNow() {
        val work = OneTimeWorkRequestBuilder<RetentionCleanupWorker>().build()
        WorkManager.getInstance(context).enqueue(work)
    }
}
