package io.github.mvolkert.entryrecorder.video

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.graphics.scale
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.data.network.HttpSnapshotClient
import io.github.mvolkert.entryrecorder.domain.device.IntercomEvent
import io.github.mvolkert.entryrecorder.domain.device.IntercomEventListener
import kotlinx.coroutines.*
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
    val deviceEntity: DeviceEntity,
    private val listener: IntercomEventListener
) {
    private val tag = "OnDeviceMotionAnalyzer"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    private var previousFrame: IntArray? = null
    private var isMotionActive = false
    private var consecutiveMotionFrames = 0
    private var consecutiveClearFrames = 0

    // Adaptive idle polling: back off while the scene is static to save CPU/network, but snap
    // straight back to the fast base interval the moment any change is seen, so detection stays
    // responsive (this app runs on a dedicated detection-first device).
    private var currentPollMs = POLL_INTERVAL_MS
    @Volatile private var recentActivity = false

    // Health window: a dead or misconfigured endpoint used to be indistinguishable from a static
    // scene, because every fetch failure returned null without a single log line. One summary per window.
    private var pollsSinceReport = 0
    private var framesSinceReport = 0
    private var peakChangedRatio = 0f
    private var lastReportAtMs = 0L

    fun start() {
        if (job?.isActive == true) return
        resetState()
        lastReportAtMs = System.currentTimeMillis()
        Log.i(tag, "Analyzing ${deviceEntity.name} at ${deviceEntity.snapshotUrl}")
        job = scope.launch { runLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        resetState()
    }

    private fun resetState() {
        previousFrame = null
        isMotionActive = false
        consecutiveMotionFrames = 0
        consecutiveClearFrames = 0
        currentPollMs = POLL_INTERVAL_MS
        recentActivity = false
        pollsSinceReport = 0
        framesSinceReport = 0
        peakChangedRatio = 0f
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
                Log.w(tag, "Frame grab/analysis failed for ${deviceEntity.name}: ${e.message}")
            }
            pollsSinceReport++
            reportHealthIfDue()
            val poll = if (recentActivity) {
                currentPollMs = POLL_INTERVAL_MS
                POLL_INTERVAL_MS
            } else {
                currentPollMs = (currentPollMs + POLL_STEP_MS).coerceAtMost(MAX_IDLE_POLL_MS)
                currentPollMs
            }
            recentActivity = false
            delay(poll.milliseconds)
        }
    }

    /** Periodic self-report: whether frames arrive at all, and how strong the biggest change was. */
    private fun reportHealthIfDue() {
        val now = System.currentTimeMillis()
        val elapsedMs = now - lastReportAtMs
        if (elapsedMs < HEALTH_REPORT_MS) return
        lastReportAtMs = now

        if (pollsSinceReport > 0 && framesSinceReport == 0) {
            Log.w(
                tag,
                "No analysis frames from ${deviceEntity.name}: $pollsSinceReport polls, 0 usable JPEGs " +
                    "in ${elapsedMs / 1000}s — endpoint not serving images (check the HttpSnapshotClient log lines)"
            )
        } else {
            Log.i(
                tag,
                "Motion analysis on ${deviceEntity.name}: $framesSinceReport/$pollsSinceReport frames, " +
                    "peak change ${(peakChangedRatio * 100).toInt()}% of ${ANALYSIS_WIDTH}x$ANALYSIS_HEIGHT " +
                    "(needs ${(MOTION_RATIO_THRESHOLD * 100).toInt()}% twice in a row), " +
                    "poll ${currentPollMs}ms"
            )
        }
        pollsSinceReport = 0
        framesSinceReport = 0
        peakChangedRatio = 0f
    }

    private fun evaluateFrame(frame: IntArray) {
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

        // Any measurable pixel movement (well below the motion trigger) keeps polling at full speed.
        if (changedRatio >= ACTIVITY_HINT_RATIO) {
            recentActivity = true
        }

        if (changedRatio >= MOTION_RATIO_THRESHOLD) {
            consecutiveMotionFrames++
            consecutiveClearFrames = 0
        } else {
            consecutiveClearFrames++
            consecutiveMotionFrames = 0
        }

        if (!isMotionActive && consecutiveMotionFrames >= REQUIRED_MOTION_FRAMES) {
            isMotionActive = true
            Log.i(tag, "Motion start on ${deviceEntity.name}: ${(changedRatio * 100).toInt()}% changed pixels")
            listener.onEvent(IntercomEvent.MotionOnDeviceStarted(deviceEntity))
        } else if (isMotionActive && consecutiveClearFrames >= REQUIRED_CLEAR_FRAMES) {
            isMotionActive = false
            Log.i(tag, "Motion end on ${deviceEntity.name}")
            listener.onEvent(IntercomEvent.MotionOnDeviceEnded(deviceEntity))
        }
    }

    private suspend fun fetchGrayscaleFrame(): IntArray? {
        val bytes = HttpSnapshotClient.fetchSnapshotBytes(deviceEntity) ?: return null
        val decoded = decodeDownscaled(bytes) ?: return null
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

    companion object {
        private const val POLL_INTERVAL_MS = 500L
        private const val POLL_STEP_MS = 250L
        private const val MAX_IDLE_POLL_MS = 1500L
        private const val HEALTH_REPORT_MS = 60_000L
        private const val ANALYSIS_WIDTH = 96
        private const val ANALYSIS_HEIGHT = 54
        private const val PIXEL_DIFF_THRESHOLD = 25
        private const val ACTIVITY_HINT_RATIO = 0.01f   // below MOTION_RATIO_THRESHOLD; speeds polling back up
        private const val MOTION_RATIO_THRESHOLD = 0.03f
        private const val REQUIRED_MOTION_FRAMES = 2
        private const val REQUIRED_CLEAR_FRAMES = 4
    }
}
