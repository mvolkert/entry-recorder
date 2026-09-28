package io.github.mvolkert.entryrecorder.worker

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import io.github.mvolkert.entryrecorder.EntryRecorderApp
import io.github.mvolkert.entryrecorder.data.local.entity.RecordingEntity
import io.github.mvolkert.entryrecorder.util.ExportHelper
import io.github.mvolkert.entryrecorder.video.ExportTranscoder
import kotlinx.coroutines.Dispatchers
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
class ExportTriggerWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val tag = "ExportTriggerWorker"

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as EntryRecorderApp
        val repository = app.repository

        try {
            val settings = repository.getSettings()
            val folder = settings.exportFolderUri
            if (folder.isBlank()) {
                Log.w(tag, "No SAF export folder configured; nothing to export. Set one in Settings.")
                return@withContext Result.failure(buildOutput(false, "No export folder configured"))
            }
            val treeUri: Uri = folder.toUri()

            val all = repository.allRecordings.first()
            if (all.isEmpty()) {
                Log.i(tag, "No recordings to export.")
                return@withContext Result.success(buildOutput(true, "No recordings"))
            }

            val scope = inputData.getString(KEY_SCOPE) ?: SCOPE_LATEST
            val targets: List<RecordingEntity> = if (scope == SCOPE_ALL) {
                all
            } else {
                val latest = all.maxByOrNull { it.timestamp } ?: return@withContext Result.success(buildOutput(true, "No recordings"))
                listOf(latest)
            }

            var exported = 0
            for (recording in targets) {
                val source = File(recording.filePath)
                if (!source.exists()) {
                    Log.w(tag, "Recording ${recording.id} file missing, skipping")
                    continue
                }
                val payload = if (settings.transcodeOnExport) {
                    runCatching { ExportTranscoder.transcodeToH264(applicationContext, recording) }
                        .onFailure { Log.w(tag, "Transcode failed for ${recording.id}, exporting raw", it) }
                        .getOrDefault(source)
                } else {
                    source
                }

                if (ExportHelper.saveFileToSafFolder(applicationContext, treeUri, payload, payload.name)) {
                    exported++
                } else {
                    Log.w(tag, "SAF write failed for ${payload.name}")
                }
            }

            Log.i(tag, "Export trigger finished: $exported/${targets.size} recordings exported (scope=$scope)")
            Result.success(buildOutput(exported > 0, "Exported $exported of ${targets.size}"))
        } catch (e: Exception) {
            Log.e(tag, "Export trigger failed", e)
            Result.retry()
        }
    }

    private fun buildOutput(ok: Boolean, message: String): Data =
        Data.Builder()
            .putBoolean(KEY_RESULT, ok)
            .putString(KEY_MESSAGE, message)
            .build()

    companion object {
        const val KEY_SCOPE = "scope"
        const val SCOPE_LATEST = "latest"
        const val SCOPE_ALL = "all"
        const val KEY_RESULT = "result"
        const val KEY_MESSAGE = "message"
    }
}
