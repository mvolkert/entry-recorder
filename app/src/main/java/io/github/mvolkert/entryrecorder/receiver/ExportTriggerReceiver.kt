package io.github.mvolkert.entryrecorder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.mvolkert.entryrecorder.worker.ExportTriggerWorker

/**
 * Exported entry point for external automation (Tasker, `adb shell am broadcast`, …) to export
 * finalized recordings into the configured SAF folder without opening the app.
 *
 * Only enqueues a background [ExportTriggerWorker]; it never reads or returns recording data to the
 * caller, so the exported surface cannot exfiltrate content — it can merely trigger an export the
 * user has already set up.
 *
 * Trigger example:
 *   adb shell am broadcast -a io.github.mvolkert.entryrecorder.action.EXPORT_RECORDINGS \
 *     --es scope all
 */
class ExportTriggerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_EXPORT) return
        val scope = when (intent.getStringExtra(EXTRA_SCOPE)?.lowercase()) {
            ExportTriggerWorker.SCOPE_ALL -> ExportTriggerWorker.SCOPE_ALL
            else -> ExportTriggerWorker.SCOPE_LATEST
        }
        Log.i(TAG, "Export trigger received (scope=$scope); enqueueing worker")
        val work = OneTimeWorkRequestBuilder<ExportTriggerWorker>()
            .setInputData(workDataOf(ExportTriggerWorker.KEY_SCOPE to scope))
            .build()
        WorkManager.getInstance(context.applicationContext).enqueue(work)
    }

    companion object {
        private const val TAG = "ExportTriggerReceiver"
        const val ACTION_EXPORT = "io.github.mvolkert.entryrecorder.action.EXPORT_RECORDINGS"
        const val EXTRA_SCOPE = "scope"
    }
}
