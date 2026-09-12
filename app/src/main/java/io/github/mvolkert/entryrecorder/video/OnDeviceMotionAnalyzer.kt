package io.github.mvolkert.entryrecorder.video

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.domain.device.IntercomEvent
import io.github.mvolkert.entryrecorder.domain.device.IntercomEventListener
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Detects motion purely by periodically grabbing snapshot frames from a device's
 * HTTP snapshot endpoint and comparing downscaled grayscale frames, instead of relying
 * on any motion detection built into the intercom device itself.
 */
class OnDeviceMotionAnalyzer(
    private val device: DeviceEntity,
    private val listener: IntercomEventListener
) {
    private val tag = "OnDeviceMotionAnalyzer"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    private var previousFrame: IntArray? = null
    private var isMotionActive = false
    private var consecutiveMotionFrames = 0
    private var consecutiveClearFrames = 0

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { runLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        previousFrame = null
        isMotionActive = false
        consecutiveMotionFrames = 0
        consecutiveClearFrames = 0
    }

    private suspend fun runLoop() {
        while (coroutineContext.isActive) {
            try {
                val frame = fetchGrayscaleFrame()
                if (frame != null) {
                    evaluateFrame(frame)
                }
            } catch (e: Exception) {
                Log.w(tag, "Frame grab/analysis failed for ${device.name}: ${e.message}")
            }
            delay(POLL_INTERVAL_MS)
        }
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

        if (changedRatio >= MOTION_RATIO_THRESHOLD) {
            consecutiveMotionFrames++
            consecutiveClearFrames = 0
        } else {
            consecutiveClearFrames++
            consecutiveMotionFrames = 0
        }

        if (!isMotionActive && consecutiveMotionFrames >= REQUIRED_MOTION_FRAMES) {
            isMotionActive = true
            listener.onEvent(IntercomEvent.MotionOnDeviceStarted(device))
        } else if (isMotionActive && consecutiveClearFrames >= REQUIRED_CLEAR_FRAMES) {
            isMotionActive = false
            listener.onEvent(IntercomEvent.MotionOnDeviceEnded(device))
        }
    }

    private fun fetchGrayscaleFrame(): IntArray? {
        val connection = (URL(device.snapshotUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 3000
            readTimeout = 3000
            val auth = "${device.username}:${device.password}"
            val encodedAuth = android.util.Base64.encodeToString(auth.toByteArray(), android.util.Base64.NO_WRAP)
            setRequestProperty("Authorization", "Basic $encodedAuth")
        }

        if (connection.responseCode != 200) return null

        val bytes = connection.inputStream.use { it.readBytes() }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val scaled = Bitmap.createScaledBitmap(bitmap, ANALYSIS_WIDTH, ANALYSIS_HEIGHT, true)
        if (scaled !== bitmap) bitmap.recycle()

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

    companion object {
        private const val POLL_INTERVAL_MS = 500L
        private const val ANALYSIS_WIDTH = 96
        private const val ANALYSIS_HEIGHT = 54
        private const val PIXEL_DIFF_THRESHOLD = 25
        private const val MOTION_RATIO_THRESHOLD = 0.03f
        private const val REQUIRED_MOTION_FRAMES = 2
        private const val REQUIRED_CLEAR_FRAMES = 4
    }
}
