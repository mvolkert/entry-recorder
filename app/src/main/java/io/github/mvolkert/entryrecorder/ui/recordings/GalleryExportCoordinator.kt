package io.github.mvolkert.entryrecorder.ui.recordings

import androidx.annotation.StringRes
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.util.ExportContext
import io.github.mvolkert.entryrecorder.util.UiStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Stage of the running single-item export, null while idle; [bodyRes] labels the strip's percent line. */
data class ExportRunProgress(val percent: Int, @StringRes val bodyRes: Int)

/**
 * Which export or download run is active and what the progress strip shows, in one place.
 *
 * Three flows feed the strip because the runs are mutually exclusive: that was automatic while their dialogs
 * covered the screen, and with a strip the list stays usable, so [start] answers a second request with the busy
 * message instead of starting a rival run over the same flows — two at once would show only one of them and
 * clear the strip when the older one ends. [finish] is the only place that clears the flows, so a run cannot
 * end with stale progress.
 *
 * Extracted from [RecordingsViewModel], which keeps the gallery concerns around it: resolving what to export
 * (folder unset, empty selection, blank video url) and the selection bookkeeping. The run is launched in the
 * [scope] the ViewModel supplies, so it cannot outlive the screen; [ExportContext] is built here because the
 * progress sinks are exactly what this class owns.
 */
class GalleryExportCoordinator(
    private val scope: CoroutineScope,
    private val strings: UiStrings,
    private val emitMessage: (text: String, short: Boolean) -> Unit,
    private val emitEvent: (RecordingsUiEvent) -> Unit,
) {
    private val _exportProgress = MutableStateFlow<ExportRunProgress?>(null)

    /** Progress of the running single-item export (transcode or folder copy), null while idle. */
    val exportProgress: StateFlow<ExportRunProgress?> = _exportProgress.asStateFlow()

    /** Done/total pair of a running multi-select export, null while idle. */
    private val _batchProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val batchProgress: StateFlow<Pair<Int, Int>?> = _batchProgress.asStateFlow()

    /** Percent (0..100) of a running server-video download, null while idle. */
    private val _serverDownload = MutableStateFlow<Int?>(null)
    val serverDownload: StateFlow<Int?> = _serverDownload.asStateFlow()

    private var activeRun: Job? = null

    /** True while a run still owns the progress strip. */
    val busy: Boolean get() = activeRun?.isActive == true

    /**
     * True (and said out loud) when a request arrives while a run still owns the progress strip. An intent with
     * its own pre-run check calls this first so the busy message keeps its original precedence over that check;
     * [start] applies the same guard for intents that have none.
     */
    fun refuseIfBusy(): Boolean {
        if (!busy) return false
        emitMessage(strings(R.string.recordings_run_busy), false)
        return true
    }

    /**
     * Mirrors what [io.github.mvolkert.entryrecorder.util.ExportRunner] reports onto the state the screen reads:
     * the runner owns the pipeline, this class only holds its progress, and the share effect is forwarded to the
     * screen (as a [RecordingsUiEvent.Share]) because only an Activity context can open the system sheet.
     */
    val context = ExportContext(
        onExportProgress = { percent, bodyRes -> _exportProgress.value = ExportRunProgress(percent, bodyRes) },
        onBatchProgress = { done, total -> _batchProgress.value = done to total },
        onServerDownload = { percent -> _serverDownload.value = percent },
        onMessage = { text, short -> emitMessage(text, short) },
        onShare = { files, metadata -> emitEvent(RecordingsUiEvent.Share(files, metadata)) },
        onCelebrate = { emitEvent(RecordingsUiEvent.ExportCompleted) },
    )

    /**
     * Starts a run over [context], refusing it with the busy message while one is already going. [block] runs in
     * the caller's coroutine, so whatever it needs from the ViewModel (resolving targets, clearing a selection)
     * stays with it; the strip is dropped in [finish] whichever way the block ends.
     */
    fun start(block: suspend (ExportContext) -> Unit): Boolean {
        if (refuseIfBusy()) return false
        activeRun = scope.launch {
            try {
                block(context)
            } finally {
                finish()
            }
        }
        return true
    }

    /**
     * Stops the running export or download. [io.github.mvolkert.entryrecorder.video.H264Transcoder] checks
     * cancellation per frame, so this lands within one frame; the strip and the unfinished file are dropped by
     * the cancelled run's own `finally`, which is where it learns which output to delete.
     */
    fun cancel() {
        activeRun?.cancel()
        emitMessage(strings(R.string.recordings_run_cancelled), false)
    }

    /** Ends the run: drops the strip by clearing every progress flow and releases [activeRun]. */
    private fun finish() {
        activeRun = null
        _exportProgress.value = null
        _batchProgress.value = null
        _serverDownload.value = null
    }
}
