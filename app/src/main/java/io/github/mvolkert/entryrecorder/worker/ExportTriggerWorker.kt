package io.github.mvolkert.entryrecorder.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.mvolkert.entryrecorder.domain.model.Recording
import io.github.mvolkert.entryrecorder.data.repository.IntercomRepository
import io.github.mvolkert.entryrecorder.di.IoDispatcher
import io.github.mvolkert.entryrecorder.util.ExportGateway
import io.github.mvolkert.entryrecorder.video.H264Transcoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Exports finalized recordings into the user's SAF export folder on demand, triggered externally
 * (e.g. Tasker sending [io.github.mvolkert.entryrecorder.receiver.ExportTriggerReceiver]). The heavy
 * H.264 transcode runs here — off the receiver and off the 24/7 monitor — so the trigger stays cheap.
 *
 * Scope is passed as input data: "latest" exports the single most recent recording, "all" exports
 * every finalized recording. Honors the transcodeOnExport setting exactly like in-app export.
 */
@HiltWorker
class ExportTriggerWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: IntercomRepository,
    private val exportGateway: ExportGateway,
    private val transcoder: H264Transcoder,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(ioDispatcher) {
        try {
            val settings = repository.getSettings()
            val folder = settings.exportFolderUri
            if (folder.isBlank()) {
                Log.w(TAG, "No SAF export folder configured; nothing to export. Set one in Settings.")
                return@withContext Result.failure(buildOutput(false, "No export folder configured"))
            }

            val all = repository.allRecordings.first()
            if (all.isEmpty()) {
                Log.i(TAG, "No recordings to export.")
                return@withContext Result.success(buildOutput(true, "No recordings"))
            }

            val scope = inputData.getString(KEY_SCOPE) ?: SCOPE_LATEST
            val targets: List<Recording> = if (scope == SCOPE_ALL) {
                all
            } else {
                val latest = all.maxByOrNull { it.timestamp } ?: return@withContext Result.success(buildOutput(true, "No recordings"))
                listOf(latest)
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
                } else {
                    Log.w(TAG, "SAF write failed for ${payload.name}")
                }
            }

            Log.i(TAG, "Export trigger finished: $exported/${targets.size} recordings exported (scope=$scope)")
            Result.success(buildOutput(exported > 0, "Exported $exported of ${targets.size}"))
        } catch (e: CancellationException) {
            // A stop or a constraint that no longer holds is not a failed export: retrying would re-run the
            // encode WorkManager just cancelled.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Export trigger failed", e)
            Result.retry()
        }
    }

    private fun buildOutput(ok: Boolean, message: String): Data =
        Data.Builder()
            .putBoolean(KEY_RESULT, ok)
            .putString(KEY_MESSAGE, message)
            .build()

    companion object {
        private const val TAG = "ExportTriggerWorker"

        const val KEY_SCOPE = "scope"
        const val SCOPE_LATEST = "latest"
        const val SCOPE_ALL = "all"
        const val KEY_RESULT = "result"
        const val KEY_MESSAGE = "message"
    }
}
