package io.github.mvolkert.entryrecorder.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.mvolkert.entryrecorder.util.ExportTriggerOutcome
import io.github.mvolkert.entryrecorder.util.ExportTriggerRunner

/**
 * Exports finalized recordings into the user's SAF export folder on demand, triggered externally
 * (e.g. Tasker sending [io.github.mvolkert.entryrecorder.receiver.ExportTriggerReceiver]). The heavy
 * H.264 transcode runs in [ExportTriggerRunner] — off the receiver and off the 24/7 monitor — so the
 * trigger stays cheap.
 *
 * The shell WorkManager understands: read the requested scope from the input, hand it to
 * [ExportTriggerRunner], and translate the resulting [ExportTriggerOutcome] into a single `Result` plus the
 * output data. Nothing about *what gets exported* is decided here — that policy lives in the runner and its
 * tests. Scope is passed as input data: "latest" exports the single most recent recording, "all" exports
 * every finalized recording. Honors the transcodeOnExport setting exactly like in-app export.
 */
@HiltWorker
class ExportTriggerWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val exportTriggerRunner: ExportTriggerRunner,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = when (
        val outcome = exportTriggerRunner.run(exportAll = inputData.getString(KEY_SCOPE) == SCOPE_ALL)
    ) {
        is ExportTriggerOutcome.Failure -> Result.failure(buildOutput(false, outcome.message))
        is ExportTriggerOutcome.Empty -> Result.success(buildOutput(true, outcome.message))
        is ExportTriggerOutcome.Done ->
            Result.success(buildOutput(outcome.exported > 0, "Exported ${outcome.exported} of ${outcome.total}"))
        ExportTriggerOutcome.Retry -> Result.retry()
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
