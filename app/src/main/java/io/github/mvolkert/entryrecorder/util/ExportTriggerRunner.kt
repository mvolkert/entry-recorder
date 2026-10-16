package io.github.mvolkert.entryrecorder.util

import android.util.Log
import io.github.mvolkert.entryrecorder.data.repository.IntercomRepository
import io.github.mvolkert.entryrecorder.di.IoDispatcher
import io.github.mvolkert.entryrecorder.video.H264Transcoder
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * The one answer the trigger-export pass can give, in terms the caller maps onto WorkManager.
 *
 * [Failure] and [Empty] both end the run without exporting anything but mean different things to the
 * scheduler — a missing SAF folder is a configuration problem the user must fix, an empty archive is a
 * success that happened to have nothing to do — so they stay separate cases rather than one boolean.
 * [Done] carries the counts the output message reports; [Retry] is the transient-failure signal that a
 * re-run may clear.
 */
sealed interface ExportTriggerOutcome {
    /** No usable export folder: a real failure the caller reports. */
    data class Failure(val message: String) : ExportTriggerOutcome

    /** Nothing to export, but not an error. */
    data class Empty(val message: String) : ExportTriggerOutcome

    /** The pass ran; [exported] of [total] targets landed in the folder. */
    data class Done(val exported: Int, val total: Int) : ExportTriggerOutcome

    /** A transient error a retry may clear. */
    data object Retry : ExportTriggerOutcome
}

/**
 * Exports finalized recordings into the user's SAF export folder, off the receiver and off the 24/7 monitor.
 *
 * Extracted from [io.github.mvolkert.entryrecorder.worker.ExportTriggerWorker] so the deciding rules — the
 * folder gate, the latest-vs-all scope, the missing-file skip, the lazy H.264 transcode with raw fallback,
 * and the SAF save counting — are ordinary code testable without WorkManager or a `Context`. What a
 * recording means for the schedule is decided here; [io.github.mvolkert.entryrecorder.worker.ExportTriggerWorker]
 * only turns the resulting [ExportTriggerOutcome] into a `Result`. Honors `transcodeOnExport` exactly like
 * in-app export.
 */
@Singleton
class ExportTriggerRunner @Inject constructor(
    private val repository: IntercomRepository,
    private val exportGateway: ExportGateway,
    private val transcoder: H264Transcoder,
    private val exportFailureMonitor: ExportFailureMonitor,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    private companion object {
        private const val TAG = "ExportTriggerRunner"
    }

    /**
     * Runs one export pass. When [exportAll] every finalized recording is a target; otherwise only the most
     * recent one is. Returns the outcome rather than throwing: a missing folder or an empty archive is data
     * the caller reports, and only a transient error asks for a retry. A cancellation always propagates.
     */
    suspend fun run(exportAll: Boolean): ExportTriggerOutcome = withContext(ioDispatcher) {
        try {
            val settings = repository.getSettings()
            val folder = settings.exportFolderUri
            if (folder.isBlank()) {
                Log.w(TAG, "No SAF export folder configured; nothing to export. Set one in Settings.")
                return@withContext ExportTriggerOutcome.Failure("No export folder configured")
            }

            val all = repository.allRecordings.first()
            if (all.isEmpty()) {
                Log.i(TAG, "No recordings to export.")
                return@withContext ExportTriggerOutcome.Empty("No recordings")
            }

            val targets = if (exportAll) {
                all
            } else {
                listOfNotNull(all.maxByOrNull { it.timestamp })
            }
            if (targets.isEmpty()) {
                return@withContext ExportTriggerOutcome.Empty("No recordings")
            }

            var exported = 0
            for (recording in targets) {
                val source = File(recording.filePath)
                if (!source.exists()) {
                    Log.w(TAG, "Recording ${recording.id} file missing, skipping")
                    continue
                }
                val payload = if (settings.transcodeOnExport) {
                    runCatching { transcoder.transcodeToH264(recording) }
                        .onFailure { Log.w(TAG, "Transcode failed for ${recording.id}, exporting raw", it) }
                        .getOrDefault(source)
                } else {
                    source
                }

                if (exportGateway.saveFileToSafFolder(folder, payload, payload.name)) {
                    exported++
                    exportFailureMonitor.recordSuccess()
                } else {
                    Log.w(TAG, "SAF write failed for ${payload.name}")
                    exportFailureMonitor.recordFailure("SAF write failed for ${payload.name}")
                }
            }

            Log.i(TAG, "Export trigger finished: $exported/${targets.size} recordings exported (all=$exportAll)")
            ExportTriggerOutcome.Done(exported = exported, total = targets.size)
        } catch (e: CancellationException) {
            // A stop or a constraint that no longer holds is not a failed export: retrying would re-run the
            // encode WorkManager just cancelled.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Export trigger failed", e)
            ExportTriggerOutcome.Retry
        }
    }
}
