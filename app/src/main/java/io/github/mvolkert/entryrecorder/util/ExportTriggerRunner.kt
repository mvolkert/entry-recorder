package io.github.mvolkert.entryrecorder.util

import timber.log.Timber
import io.github.mvolkert.entryrecorder.data.repository.IntercomRepository
import io.github.mvolkert.entryrecorder.di.IoDispatcher
import io.github.mvolkert.entryrecorder.domain.model.Recording
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
 * in-app export, and [runOne] carries the same pass for a single recording the moment it finalizes.
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
            if (folder.isBlank()) return@withContext noExportFolder()

            val all = repository.allRecordings.first()
            val targets = if (exportAll) {
                all
            } else {
                listOfNotNull(all.maxByOrNull { it.timestamp })
            }
            exportPass(folder, targets, transcode = settings.transcodeOnExport, scopeLabel = "all=$exportAll")
        } catch (e: CancellationException) {
            // A stop or a constraint that no longer holds is not a failed export: retrying would re-run the
            // encode WorkManager just cancelled.
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Export trigger failed")
            ExportTriggerOutcome.Retry
        }
    }

    /**
     * Exports exactly one recording — the automatic post-finalize mirror the user opted into with the
     * auto-transcode switch — through the same pass the trigger uses, so an archived clip and a manually
     * exported one cannot drift apart. [transcode] is an explicit mode rather than the
     * `transcodeOnExport` setting, because the row that asked for the mirror chose H.264 and the switch the
     * user flips for in-app export must not decide what the archive contains. When the encode throws, the raw
     * MKV is delivered instead, so the archive never has holes.
     */
    suspend fun runOne(recordingId: Long, transcode: Boolean): ExportTriggerOutcome = withContext(ioDispatcher) {
        try {
            val settings = repository.getSettings()
            val folder = settings.exportFolderUri
            if (folder.isBlank()) return@withContext noExportFolder()

            val recording = repository.getRecordingById(recordingId)
            if (recording == null) {
                // The row can only be gone because the user deleted the recording while this export sat in
                // the queue, which is a success that had nothing left to do rather than a failure.
                Timber.tag(TAG).i("Recording $recordingId no longer exists; nothing to export.")
                return@withContext ExportTriggerOutcome.Empty("Recording $recordingId no longer exists")
            }
            exportPass(folder, listOf(recording), transcode = transcode, scopeLabel = "recording=$recordingId")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Auto-export of recording $recordingId failed")
            ExportTriggerOutcome.Retry
        }
    }

    /**
     * The pass behind both entries: deliver every [targets] recording into [folder] under [transcode] and
     * report the counts. An empty target list is the same empty success whichever scope produced it, and a
     * recording whose file already went is skipped rather than counted.
     */
    private suspend fun exportPass(
        folder: String,
        targets: List<Recording>,
        transcode: Boolean,
        scopeLabel: String,
    ): ExportTriggerOutcome {
        if (targets.isEmpty()) {
            Timber.tag(TAG).i("No recordings to export ($scopeLabel).")
            return ExportTriggerOutcome.Empty("No recordings")
        }

        var exported = 0
        for (recording in targets) {
            val source = File(recording.filePath)
            if (!source.exists()) {
                Timber.tag(TAG).w("Recording ${recording.id} file missing, skipping")
                continue
            }
            val payload = if (transcode) {
                runCatching { transcoder.transcodeToH264(recording) }
                    .onFailure { Timber.tag(TAG).w(it, "Transcode failed for ${recording.id}, exporting raw") }
                    .getOrDefault(source)
            } else {
                source
            }

            if (exportGateway.saveFileToSafFolder(folder, payload, payload.name)) {
                exported++
                exportFailureMonitor.recordSuccess()
            } else {
                Timber.tag(TAG).w("SAF write failed for ${payload.name}")
                exportFailureMonitor.recordFailure("SAF write failed for ${payload.name}")
            }
        }

        Timber.tag(TAG).i("Export pass finished ($scopeLabel): $exported/${targets.size} recordings exported")
        return ExportTriggerOutcome.Done(exported = exported, total = targets.size)
    }

    /** The folder gate both entries share: a missing folder is a configuration failure the user must fix. */
    private fun noExportFolder(): ExportTriggerOutcome {
        Timber.tag(TAG).w("No SAF export folder configured; nothing to export. Set one in Settings.")
        return ExportTriggerOutcome.Failure("No export folder configured")
    }
}
