package io.github.mvolkert.entryrecorder.video

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.data.local.entity.RecordingEntity
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.data.repository.IntercomRepository
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@OptIn(UnstableApi::class)
class RtspStreamRecorder(
    private val context: Context,
    private val repository: IntercomRepository
) {
    private val tag = "RtspStreamRecorder"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeRecordings = ConcurrentHashMap<Long, ActiveRecordingJob>()

    private data class ActiveRecordingJob(
        val deviceId: Long,
        val eventType: EventType,
        val startTimeMs: Long,
        val outputFile: File,
        val job: Job
    )

    fun isRecording(deviceId: Long): Boolean = activeRecordings.containsKey(deviceId)

    /**
     * Start recording an RTSP/Snapshot video sequence for a given device and trigger event
     */
    @Synchronized
    fun startRecording(device: DeviceEntity, eventType: EventType, maxDurationSeconds: Int = 60) {
        if (activeRecordings.containsKey(device.id)) {
            Log.d(tag, "Device ${device.id} is already recording")
            return
        }

        val timestampStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val recordingsDir = File(context.filesDir, "recordings").apply { mkdirs() }
        val outputFile = File(recordingsDir, "REC_${device.id}_${eventType.name}_$timestampStr.mp4")

        val startTime = System.currentTimeMillis()
        Log.i(tag, "Starting recording for ${device.name} [${eventType.name}] -> ${outputFile.name}")

        val job = scope.launch {
            try {
                // Recording capture loop: captures snapshot frames / RTSP stream chunks
                recordStreamFrames(device, outputFile, maxDurationSeconds)
            } catch (e: CancellationException) {
                Log.d(tag, "Recording cancelled/stopped normally for ${device.name}")
            } catch (e: Exception) {
                Log.e(tag, "Error during recording for ${device.name}", e)
            } finally {
                finalizeRecording(device, eventType, startTime, outputFile)
            }
        }

        activeRecordings[device.id] = ActiveRecordingJob(
            deviceId = device.id,
            eventType = eventType,
            startTimeMs = startTime,
            outputFile = outputFile,
            job = job
        )
    }

    /**
     * Stops an ongoing recording and commits it to database
     */
    @Synchronized
    fun stopRecording(deviceId: Long) {
        val active = activeRecordings.remove(deviceId)
        if (active != null) {
            Log.i(tag, "Stopping active recording for device $deviceId")
            active.job.cancel()
        }
    }

    private suspend fun recordStreamFrames(device: DeviceEntity, outputFile: File, maxDurationSeconds: Int) = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + (maxDurationSeconds * 1000L)
        val snapshotUrl = device.snapshotUrl

        // Capture snapshot frames into file / buffer
        FileOutputStream(outputFile).use { fos ->
            while (isActive && System.currentTimeMillis() < deadline) {
                try {
                    val url = URL(snapshotUrl)
                    val connection = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 3000
                        readTimeout = 3000
                        val auth = "${device.username}:${device.password}"
                        val encodedAuth = android.util.Base64.encodeToString(auth.toByteArray(), android.util.Base64.NO_WRAP)
                        setRequestProperty("Authorization", "Basic $encodedAuth")
                    }

                    if (connection.responseCode == 200) {
                        connection.inputStream.use { input ->
                            input.copyTo(fos)
                        }
                    }
                } catch (e: Exception) {
                    // Ignored single frame grab errors
                }
                delay(200) // ~5 fps capture interval
            }
        }
    }

    private suspend fun finalizeRecording(
        device: DeviceEntity,
        eventType: EventType,
        startTimeMs: Long,
        outputFile: File
    ) {
        activeRecordings.remove(device.id)
        val durationSec = ((System.currentTimeMillis() - startTimeMs) / 1000L).coerceAtLeast(1L)
        val fileSize = if (outputFile.exists()) outputFile.length() else 0L

        if (fileSize > 0) {
            val thumbPath = ThumbnailUtil.extractAndSaveThumbnail(context, outputFile.absolutePath, device.id)
            val recording = RecordingEntity(
                deviceId = device.id,
                deviceName = device.name,
                eventType = eventType,
                timestamp = startTimeMs,
                durationSeconds = durationSec,
                filePath = outputFile.absolutePath,
                fileSizeBytes = fileSize,
                thumbnailPath = thumbPath
            )
            val id = repository.insertRecording(recording)
            Log.i(tag, "Recording saved: id=$id, duration=${durationSec}s, size=$fileSize bytes")
        } else {
            Log.w(tag, "Recording produced empty file, discarding ${outputFile.name}")
            if (outputFile.exists()) outputFile.delete()
        }
    }
}
