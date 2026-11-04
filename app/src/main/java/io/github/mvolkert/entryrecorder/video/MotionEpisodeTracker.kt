package io.github.mvolkert.entryrecorder.video

/**
 * The episode half of [OnDeviceMotionAnalyzer]'s decision, free of framework types and of the fetch so the timing
 * rules can be driven by a test clock.
 *
 * Opening an episode stays a count of consecutive changed comparisons: a comparison is inherently between two fetched
 * frames, so a time bound would have to be measured across gaps the endpoint controls, and the shortest one any such
 * rule could confirm on a fully backed-off scene is longer than the bar it would then be missing.
 *
 * Closing is a duration. The episode ends once the quiet frames *themselves* have spanned [clearStillnessMs],
 * measured from the first quiet comparison, so an un-sampled gap before that frame never counts as stillness and the
 * preset demands the same number of seconds whether the loop is at its base interval, at its idle ceiling, or at the
 * reduced pace a local recording forces.
 */
internal class MotionEpisodeTracker(
    private val requiredMotionFrames: Int,
    private val clearStillnessMs: Long,
) {
    /** Whether a motion episode is open: started and not yet ended. */
    @Volatile
    var isEpisodeActive = false
        private set

    private var consecutiveMotionFrames = 0
    private var consecutiveClearFrames = 0

    // Monotonic readings only: a wall clock that jumps mid-episode would otherwise close an episode early or keep it
    // open on a duration nobody observed.
    private var runStartAtMs = 0L
    private var firstClearFrameAtMs = 0L

    /**
     * Records the outcome of one comparison scored at [elapsedMs], a
     * [io.github.mvolkert.entryrecorder.domain.model.TimeProvider.elapsedMillis] reading, and returns what that did
     * to the episode.
     */
    fun onComparison(changeDetected: Boolean, elapsedMs: Long): MotionDecision {
        if (changeDetected) {
            if (consecutiveMotionFrames == 0) runStartAtMs = elapsedMs
            consecutiveMotionFrames++
            consecutiveClearFrames = 0
            if (!isEpisodeActive && consecutiveMotionFrames >= requiredMotionFrames) {
                isEpisodeActive = true
                return MotionDecision.Started(
                    frames = consecutiveMotionFrames,
                    spanMs = elapsedMs - runStartAtMs,
                )
            }
            return MotionDecision.None
        }

        consecutiveMotionFrames = 0
        // The post-increment reads the count before this frame, so the first quiet frame of a run anchors here.
        if (consecutiveClearFrames++ == 0) firstClearFrameAtMs = elapsedMs
        val stillnessMs = elapsedMs - firstClearFrameAtMs
        if (isEpisodeActive && stillnessMs >= clearStillnessMs) {
            isEpisodeActive = false
            val frames = consecutiveClearFrames
            consecutiveClearFrames = 0
            return MotionDecision.Ended(stillnessMs = stillnessMs, frames = frames)
        }
        return MotionDecision.None
    }

    /** Drops the run counters and the open episode, as a rebuilt or stopped analyzer must. */
    fun reset() {
        isEpisodeActive = false
        consecutiveMotionFrames = 0
        consecutiveClearFrames = 0
        runStartAtMs = 0L
        firstClearFrameAtMs = 0L
    }
}

/** What one scored comparison did to the motion episode. */
internal sealed interface MotionDecision {
    data object None : MotionDecision

    /** The changed run that met the preset's bar, taken over [spanMs] of elapsed time. */
    data class Started(val frames: Int, val spanMs: Long) : MotionDecision

    /** The stillness that met the preset's duration, [frames] quiet comparisons over [stillnessMs]. */
    data class Ended(val stillnessMs: Long, val frames: Int) : MotionDecision
}
