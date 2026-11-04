package io.github.mvolkert.entryrecorder.video

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import timber.log.Timber
import androidx.core.graphics.scale
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.domain.model.Device
import io.github.mvolkert.entryrecorder.data.model.ConnectionCapability
import io.github.mvolkert.entryrecorder.data.model.ConnectionQuality
import io.github.mvolkert.entryrecorder.data.model.MotionSensitivity
import io.github.mvolkert.entryrecorder.data.network.HttpSnapshotClient
import io.github.mvolkert.entryrecorder.domain.device.IntercomEvent
import io.github.mvolkert.entryrecorder.domain.device.IntercomEventListener
import io.github.mvolkert.entryrecorder.domain.model.SystemTimeProvider
import io.github.mvolkert.entryrecorder.domain.model.TimeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Detects motion purely by periodically grabbing snapshot frames from a device's
 * HTTP snapshot endpoint and comparing downscaled grayscale frames, instead of relying
 * on any motion detection built into the intercom device itself.
 *
 * Frames come from [HttpSnapshotClient], the same keep-alive path the recorder and the live view
 * use, so auth, timeouts and failure reporting behave identically wherever the camera is polled.
 */
class OnDeviceMotionAnalyzer(
    val device: Device,
    private val listener: IntercomEventListener,
    private val snapshotClient: HttpSnapshotClient,
    private val ioDispatcher: CoroutineDispatcher,
    // While a *local* recording is active the recorder's capture loop on this phone polls the same
    // snapshot endpoint, so the analyzer backs off to a coarse interval instead of competing for a
    // serial device. Server-side recordings must NOT report true here: the phone is not the poller
    // then, and backing off would only slow detection. Defaults to "never recording" so the analyzer
    // stays usable without a recorder wired in.
    private val isRecording: () -> Boolean = { false },
    // Durations are measured from the elapsed reading so a wall-clock jump cannot open or close an episode; the
    // pre-roll ring keeps the wall clock because its stamp becomes the recorded clip's start time on disk.
    private val timeProvider: TimeProvider = SystemTimeProvider,
) {
    private val tag = "OnDeviceMotionAnalyzer"
    private val scope = CoroutineScope(ioDispatcher + SupervisorJob())
    private var job: Job? = null

    private var previousFrame: IntArray? = null

    /**
     * Whether a motion episode is open: started and not yet ended. Public because whoever owns this analyzer has
     * to close the episode it started when the analyzer is torn down; a stopped mid-episode analyzer would
     * otherwise leave its recording with nobody scheduled to stop it.
     */
    val isEpisodeActive: Boolean get() = tracker.isEpisodeActive

    // Bounded ring of the most recent raw JPEGs, so a recording triggered by motion can prepend the
    // frames from just before the trigger instead of starting ~1s after the person already arrived.
    private val preRoll = ArrayDeque<PreRollFrame>()

    /**
     * Trigger bar, changed-frame count, stillness duration and polling intervals resolved from the device's chosen
     * [MotionSensitivity]. BALANCED reproduces the numbers shipped before the setting existed, expressed in the unit
     * each of them cost at that preset's own polling pace, so a device that never touched the setting is unchanged.
     */
    private val tuning = motionTuningFor(device.motionSensitivity)

    // The open/close rules themselves, kept apart from the fetch so a test clock can drive them.
    private val tracker = MotionEpisodeTracker(
        requiredMotionFrames = tuning.requiredMotionFrames,
        clearStillnessMs = tuning.clearStillnessMs,
    )

    /**
     * The slice of the device row this analyzer reads. [io.github.mvolkert.entryrecorder.service.DeviceMonitorPool]
     * rebuilds when it changes and keeps a running analyzer through any other edit, so a settings change the
     * analysis does not consume no longer throws away the frame reference and the pre-roll ring mid-episode.
     */
    val analysisKey: MotionAnalysisKey = keyFor(device)

    /** Ring size for [bufferPreRoll]: the device's configured pre-roll count, clamped to the sane range. */
    private val preRollCapacity = device.effectiveMotionPreRollFrames

    // Adaptive idle polling: back off while the scene is static to save CPU/network, but snap
    // straight back to the fast base interval the moment any change is seen, so detection stays
    // responsive (this app runs on a dedicated detection-first device).
    private var currentPollMs = tuning.pollIntervalMs
    @Volatile private var recentActivity = false

    // Health window: a dead or misconfigured endpoint used to be indistinguishable from a static
    // scene, because every fetch failure returned null without a single log line. One summary per window.
    private var pollsSinceReport = 0
    private var framesSinceReport = 0
    private var scoredFramesSinceReport = 0
    private var peakChangedRatio = 0f
    private var lastReportAtElapsedMs = 0L

    // Last connection quality handed to the listener, so a state change is reported exactly once
    // instead of on every poll that comes back empty.
    private var reportedQuality = ConnectionQuality.ONLINE

    fun start() {
        if (job?.isActive == true) return
        resetState()
        reportedQuality = snapshotClient.snapshotQuality(device.id)
        lastReportAtElapsedMs = timeProvider.elapsedMillis()
        Timber.tag(tag).i("Analyzing ${device.name} at ${device.snapshotUrl}")
        job = scope.launch { runLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        resetState()
    }

    private fun resetState() {
        previousFrame = null
        tracker.reset()
        currentPollMs = tuning.pollIntervalMs
        recentActivity = false
        pollsSinceReport = 0
        framesSinceReport = 0
        scoredFramesSinceReport = 0
        peakChangedRatio = 0f
        synchronized(preRoll) { preRoll.clear() }
    }

    private suspend fun runLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                val frame = fetchGrayscaleFrame()
                if (frame != null) {
                    framesSinceReport++
                    evaluateFrame(frame)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(tag).w("Frame grab/analysis failed for ${device.name}: ${e.message}")
            }
            pollsSinceReport++
            val poll = when {
                // Recording in progress: the recorder owns the endpoint at snapshotFps, so poll slowly
                // to avoid oversubscribing it, while still catching motion-end to schedule the stop.
                isRecording() -> RECORDING_POLL_MS
                recentActivity -> tuning.pollIntervalMs
                else -> (currentPollMs + POLL_STEP_MS).coerceAtMost(tuning.maxIdlePollMs)
            }
            currentPollMs = poll
            recentActivity = false
            reportHealthIfDue()
            reportConnectionEdge()
            delay(poll.milliseconds)
        }
    }

    /**
     * Turns the failure streak of the shared snapshot client into a real [IntercomEvent.ConnectionState]
     * edge. This loop runs 24/7, so it — not the occasionally-running recorder — is what makes a camera
     * that stopped serving frames visible in the UI instead of only in logcat.
     */
    private fun reportConnectionEdge() {
        val quality = snapshotClient.snapshotQuality(device.id)
        if (quality == reportedQuality) return
        reportedQuality = quality
        val failures = snapshotClient.consecutiveFailures(device.id)
        val message = if (quality == ConnectionQuality.ONLINE)
            "Snapshot frames recovered after $failures missed polls"
        else "Snapshot endpoint delivered no frame for $failures polls in a row"
        listener.onEvent(
            IntercomEvent.ConnectionState(device, quality, message, ConnectionCapability.SNAPSHOT)
        )
    }

    /**
     * Periodic self-report: whether frames arrive at all, how strong the biggest change was, and at what rate this
     * analyzer scored them over the window it actually measured.
     */
    internal fun reportHealthIfDue() {
        val now = timeProvider.elapsedMillis()
        val windowMs = now - lastReportAtElapsedMs
        if (windowMs < HEALTH_REPORT_MS) return
        lastReportAtElapsedMs = now

        val window = MotionWindow(
            deviceName = device.name,
            scoredFrames = scoredFramesSinceReport,
            polls = pollsSinceReport,
            peakChangedRatio = peakChangedRatio,
            windowMs = windowMs,
            pollMs = currentPollMs,
            recording = isRecording(),
            analysisWidth = ANALYSIS_WIDTH,
            analysisHeight = ANALYSIS_HEIGHT,
        )
        if (pollsSinceReport > 0 && framesSinceReport == 0) {
            Timber.tag(tag).w(MotionAnalysisReport.noFrames(window))
        } else {
            Timber.tag(tag).i(MotionAnalysisReport.health(window, tuning, device.motionSensitivity))
        }
        pollsSinceReport = 0
        framesSinceReport = 0
        scoredFramesSinceReport = 0
        peakChangedRatio = 0f
    }

    /**
     * Scores the fetched frame against the previous one and lets [tracker] decide what that does to the episode.
     * Reachable from a test because everything it consumes is a grid of gray values; the JPEG decode above it is the
     * part a JVM test cannot drive.
     */
    internal fun evaluateFrame(frame: IntArray) {
        val prev = previousFrame
        previousFrame = frame
        if (prev == null || prev.size != frame.size) return

        var changedPixels = 0
        for (i in frame.indices) {
            if (kotlin.math.abs(frame[i] - prev[i]) > PIXEL_DIFF_THRESHOLD) {
                changedPixels++
            }
        }
        val changedRatio = changedPixels.toFloat() / frame.size
        peakChangedRatio = maxOf(peakChangedRatio, changedRatio)
        scoredFramesSinceReport++

        // Any measurable pixel movement (well below the motion trigger) keeps polling at full speed.
        if (changedRatio >= ACTIVITY_HINT_RATIO) {
            recentActivity = true
        }

        val decision = tracker.onComparison(
            changeDetected = changedRatio >= tuning.ratioThreshold,
            elapsedMs = timeProvider.elapsedMillis(),
        )
        when (decision) {
            MotionDecision.None -> Unit
            is MotionDecision.Started -> {
                Timber.tag(tag).i(
                    MotionAnalysisReport.start(device.name, changedRatio, decision.frames, decision.spanMs)
                )
                listener.onEvent(IntercomEvent.MotionOnDeviceStarted(device))
            }

            is MotionDecision.Ended -> {
                Timber.tag(tag).i(
                    MotionAnalysisReport.end(device.name, decision.stillnessMs, decision.frames)
                )
                listener.onEvent(IntercomEvent.MotionOnDeviceEnded(device))
            }
        }
    }

    private suspend fun fetchGrayscaleFrame(): IntArray? {
        val bytes = snapshotClient.fetchSnapshotBytes(device) ?: return null
        val decoded = decodeDownscaled(bytes) ?: return null
        // The ring exists to prepend the approach frames of a *new* trigger. While a local recording is
        // running those frames are already going to disk, so holding a ring of full-res JPEGs per poll
        // only costs heap and GC on a 24/7 loop; the ring refills as soon as the recording stops.
        if (!isRecording()) bufferPreRoll(bytes)
        val scaled = if (decoded.width == ANALYSIS_WIDTH && decoded.height == ANALYSIS_HEIGHT) {
            decoded
        } else {
            decoded.scale(ANALYSIS_WIDTH, ANALYSIS_HEIGHT)
        }
        if (scaled !== decoded) decoded.recycle()

        val pixels = IntArray(ANALYSIS_WIDTH * ANALYSIS_HEIGHT)
        scaled.getPixels(pixels, 0, ANALYSIS_WIDTH, 0, 0, ANALYSIS_WIDTH, ANALYSIS_HEIGHT)
        scaled.recycle()

        return IntArray(pixels.size) { i ->
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            (r + g + b) / 3
        }
    }

    /** JPEG decode is the expensive part of this 24/7 loop, so decode straight down to the analysis grid. */
    private fun decodeDownscaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= ANALYSIS_WIDTH &&
            bounds.outHeight / (sampleSize * 2) >= ANALYSIS_HEIGHT
        ) {
            sampleSize *= 2
        }
        return BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        )
    }

    /** Keeps the last [preRollCapacity] captured JPEGs, oldest evicted first, stamped with the wall clock. */
    internal fun bufferPreRoll(jpeg: ByteArray) {
        synchronized(preRoll) {
            preRoll.addLast(PreRollFrame(timeProvider.currentTimeMillis(), jpeg))
            while (preRoll.size > preRollCapacity) preRoll.removeFirst()
        }
    }

    /** Frames captured just before the current trigger, oldest first; empties the buffer. */
    fun drainPreRoll(): List<PreRollFrame> = synchronized(preRoll) {
        val copy = preRoll.toList()
        preRoll.clear()
        copy
    }

    companion object {
        /** The device fields the analyzer consumes; anything else may change without rebuilding it. */
        fun keyFor(device: Device): MotionAnalysisKey = MotionAnalysisKey(
            deviceId = device.id,
            snapshotUrl = device.snapshotUrl,
            username = device.username,
            password = device.password,
            motionSensitivity = device.motionSensitivity,
            preRollFrames = device.effectiveMotionPreRollFrames,
        )

        private const val POLL_STEP_MS = 250L
        private const val RECORDING_POLL_MS = 1500L
        private const val HEALTH_REPORT_MS = 60_000L
        private const val ANALYSIS_WIDTH = 96
        private const val ANALYSIS_HEIGHT = 54
        private const val PIXEL_DIFF_THRESHOLD = 25
        // Well below every preset's trigger bar: any real movement only snaps polling back to the
        // preset's base interval, it never triggers a recording on its own.
        private const val ACTIVITY_HINT_RATIO = 0.01f
    }
}

/**
 * The configuration an [OnDeviceMotionAnalyzer] consumes, declared next to the analyzer that reads it so adding
 * a field there means adding it here. Equality is what the monitor pool compares to decide whether a running
 * analyzer has to be rebuilt.
 */
data class MotionAnalysisKey(
    val deviceId: Long,
    val snapshotUrl: String,
    val username: String,
    val password: String,
    val motionSensitivity: MotionSensitivity,
    val preRollFrames: Int,
) {
    /** Mirrors [Device.toString]: the generated form would put the snapshot password into any log line. */
    override fun toString(): String =
        "MotionAnalysisKey(deviceId=$deviceId, snapshotUrl=$snapshotUrl, username=$username, " +
            "password=<redacted>, motionSensitivity=$motionSensitivity, preRollFrames=$preRollFrames)"
}

/**
 * Analyzer numbers for one [MotionSensitivity] preset. BALANCED reproduces the constants shipped before
 * the setting existed (3% / 2 frames / 500→1500 ms idle backoff); SENSITIVE loosens and speeds them for
 * fast crossers at a higher battery/network cost, POWER_SAVER tightens and slows them for calm scenes.
 *
 * The open rule counts changed frames and the close rule is a stillness duration: a comparison is inherently
 * between two fetched frames, while the seconds a scene has to stay quiet is what the user means by the preset.
 * Each duration is what that preset's old quiet-frame count cost at its own base interval, so the pace the loop
 * settles on no longer stretches the episode out.
 */
internal class MotionTuning(
    val ratioThreshold: Float,
    val requiredMotionFrames: Int,
    val clearStillnessMs: Long,
    val pollIntervalMs: Long,
    val maxIdlePollMs: Long,
)

internal fun motionTuningFor(sensitivity: MotionSensitivity): MotionTuning = when (sensitivity) {
    MotionSensitivity.SENSITIVE -> MotionTuning(0.02f, 1, 1_200L, 400L, 900L)
    MotionSensitivity.BALANCED -> MotionTuning(0.03f, 2, 2_000L, 500L, 1500L)
    MotionSensitivity.POWER_SAVER -> MotionTuning(0.05f, 3, 4_800L, 800L, 2500L)
}
