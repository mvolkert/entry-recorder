package io.github.mvolkert.entryrecorder.worker

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Queueing an automatic post-finalize export, as a JVM-only contract: the capture loop has to hand a finished
 * recording to the export chain without holding a `Context` or knowing how WorkManager serializes work, so the
 * finalize branch stays ordinary code the device-free tests can drive.
 */
interface AutoExportScheduler {

    /**
     * Queues a transcode-and-export run for [recordingId] on the shared export chain. Non-blocking: the caller
     * goes straight back to finalizing clips while this one waits its turn behind whatever is encoding.
     */
    fun enqueueTranscodeExport(recordingId: Long)
}

/**
 * The queue the export spec describes, expressed as data: which chain an automatic export joins and with what
 * policy. [ExistingWorkPolicy.APPEND_OR_REPLACE] on one [uniqueWorkName] is the whole serialization mechanism —
 * a named chain runs its work one at a time in submission order, WorkManager persists it so a queued export
 * survives the process ending, and a new request joins the back rather than replacing what is already waiting.
 * The impl enqueues through this value and `AutoExportQueueTest` pins it, because WorkManager's own scheduling
 * can only be observed on device.
 */
data class AutoExportQueue(val uniqueWorkName: String, val enqueuePolicy: ExistingWorkPolicy)

val AUTO_EXPORT_QUEUE: AutoExportQueue = AutoExportQueue(
    uniqueWorkName = ExportTriggerWorker.UNIQUE_WORK_NAME,
    enqueuePolicy = ExistingWorkPolicy.APPEND_OR_REPLACE,
)

/** The device-backed implementation: WorkManager through the application context. */
class AndroidAutoExportScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : AutoExportScheduler {
    override fun enqueueTranscodeExport(recordingId: Long) {
        val work = OneTimeWorkRequestBuilder<ExportTriggerWorker>()
            .setInputData(
                workDataOf(
                    ExportTriggerWorker.KEY_RECORDING_ID to recordingId,
                    ExportTriggerWorker.KEY_TRANSCODE to true,
                )
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(AUTO_EXPORT_QUEUE.uniqueWorkName, AUTO_EXPORT_QUEUE.enqueuePolicy, work)
    }
}
