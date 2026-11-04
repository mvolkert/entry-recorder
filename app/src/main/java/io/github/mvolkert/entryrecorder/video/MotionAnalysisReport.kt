package io.github.mvolkert.entryrecorder.video

import io.github.mvolkert.entryrecorder.data.model.MotionSensitivity
import java.util.Locale

/**
 * One measurement window of [OnDeviceMotionAnalyzer]'s own counting, as its loop recorded it. Declared apart from the
 * analyzer so the wording of the reports is fixed by [MotionAnalysisReport] alone and can be pinned from a test.
 */
internal data class MotionWindow(
    val deviceName: String,
    /** Comparisons scored — the delivered rate's numerator, because a fetched frame decides nothing on its own. */
    val scoredFrames: Int,
    val polls: Int,
    val peakChangedRatio: Float,
    /** How long the window actually spanned, rather than the nominal report interval. */
    val windowMs: Long,
    /** The interval the loop is honouring right now: preset base, backed off, or the recording pace. */
    val pollMs: Long,
    val recording: Boolean,
    val analysisWidth: Int,
    val analysisHeight: Int,
)

/**
 * The analyzer's log lines. Every number in them is one this analyzer produced itself: the rate is the comparisons it
 * scored divided by the window it measured, never an average of whoever else happens to poll the same endpoint.
 */
internal object MotionAnalysisReport {

    /** Comparisons scored per second over the measured window; zero only when nothing was scored. */
    fun scoredFps(window: MotionWindow): Float =
        if (window.windowMs <= 0L) 0f else window.scoredFrames * 1000f / window.windowMs

    /** The per-minute summary: what was scored, how strong the biggest change was, and the cadence behind it. */
    fun health(window: MotionWindow, tuning: MotionTuning, sensitivity: MotionSensitivity): String =
        "Motion analysis on ${window.deviceName}: ${window.scoredFrames}/${window.polls} polls scored, " +
            "peak change ${percent(window.peakChangedRatio)} of ${window.analysisWidth}x${window.analysisHeight} " +
            "(needs ${percent(tuning.ratioThreshold)} on ${tuning.requiredMotionFrames} in a row, $sensitivity), " +
            "${scoredFps(window).asFps()} over ${window.windowMs.asSeconds()}, ${cadence(window, tuning)}"

    /**
     * A window that produced no usable frame at all. Says something about the endpoint's supply, not about a rate:
     * the polls it counted are the evidence, and a slow-but-healthy scene never reaches this line.
     */
    fun noFrames(window: MotionWindow): String =
        "No analysis frames from ${window.deviceName}: ${window.polls} polls, 0 usable JPEGs in " +
            "${window.windowMs.asSeconds()} — endpoint not serving images " +
            "(check the HttpSnapshotClient log lines)"

    fun start(deviceName: String, changedRatio: Float, frames: Int, spanMs: Long): String =
        "Motion start on $deviceName: ${percent(changedRatio)} changed pixels, " +
            "over $frames frames in ${spanMs.asSeconds()}"

    fun end(deviceName: String, stillnessMs: Long, frames: Int): String =
        "Motion end on $deviceName after ${stillnessMs.asSeconds()} still ($frames frames)"

    private fun cadence(window: MotionWindow, tuning: MotionTuning): String = when {
        window.recording -> "poll ${window.pollMs}ms (recording pace)"
        window.pollMs <= tuning.pollIntervalMs -> "poll ${window.pollMs}ms (base)"
        else -> "poll ${window.pollMs}ms (idle backoff, ceiling ${tuning.maxIdlePollMs}ms)"
    }

    private fun percent(ratio: Float): String = "${(ratio * 100).toInt()}%"

    // Each value formats on its own: the lines carry literal percent signs, which a shared format string would
    // read as a conversion. Locale.US keeps the decimals plain digits, so a greppable log line does not turn into
    // "2,0 fps" on a German phone.
    private fun Long.asSeconds(): String = "%.1fs".format(Locale.US, this / 1000.0)

    private fun Float.asFps(): String = "%.1f fps".format(Locale.US, this)
}
