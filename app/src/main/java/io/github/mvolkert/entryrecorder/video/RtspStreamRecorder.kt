package io.github.mvolkert.entryrecorder.video

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.data.local.entity.RecordingEntity
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.data.model.RecordingMode
import io.github.mvolkert.entryrecorder.data.model.StreamProtocol
import io.github.mvolkert.entryrecorder.data.network.HttpSnapshotClient
import io.github.mvolkert.entryrecorder.data.network.MjpegStreamReader
import io.github.mvolkert.entryrecorder.data.repository.IntercomRepository
import io.github.mvolkert.entryrecorder.data.server.ServerRecordingClient
import io.github.mvolkert.entryrecorder.util.ExportHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

@OptIn(UnstableApi::class)
class RtspStreamRecorder(
    private val context: Context,
    private val repository: IntercomRepository,
    private val serverClient: ServerRecordingClient = ServerRecordingClient()
) {
    private val tag = "RtspStreamRecorder"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeRecordings = ConcurrentHashMap<Long, ActiveRecordingJob>()
    private val activeServerRecordings = ConcurrentHashMap<Long, Boolean>()

    // Observable recording status: the plain ConcurrentHashMaps above are invisible to Compose, so
    // every mutation also publishes the affected device ids here (local + server recordings). The
    // Live screen observes this StateFlow through LiveViewModel instead of polling isRecording().
    private val _activeDeviceIds = MutableStateFlow<Set<Long>>(emptySet())
    /** Device ids that currently have an active recording (local or on the server). */
    val activeDeviceIds: StateFlow<Set<Long>> = _activeDeviceIds.asStateFlow()

    private fun publishActiveIds() {
        _activeDeviceIds.value = (activeRecordings.keys + activeServerRecordings.keys).toSet()
    }

    private data class ActiveRecordingJob(
        val deviceId: Long,
        val eventType: EventType,
        val startTimeMs: Long,
        val outputFile: File,
        val job: Job
    )

    fun isRecording(deviceId: Long): Boolean = deviceId in _activeDeviceIds.value

    /**
     * Start recording an RTSP/Snapshot video sequence for a given device and trigger event.
     * Evaluates whether to record via Python server or locally in-app (default).
     */
    @Synchronized
    fun startRecording(device: DeviceEntity, eventType: EventType, maxDurationSeconds: Int = 60) {
        if (isRecording(device.id)) {
            Log.d(tag, "Device ${device.id} is already recording")
            return
        }

        scope.launch {
            val settings = repository.getSettings()

            if (settings.recordingMode == RecordingMode.PYTHON_SERVER) {
                Log.i(tag, "Initiating server recording on ${settings.serverBaseUrl} for ${device.name}")
                val result = serverClient.startRecording(
                    serverUrl = settings.serverBaseUrl,
                    apiKey = settings.serverApiKey.ifBlank { null },
                    device = device,
                    eventType = eventType,
                    durationSeconds = maxDurationSeconds
                )

                if (result.isSuccess) {
                    activeServerRecordings[device.id] = true
                    publishActiveIds()
                    // The server also auto-stops after duration_seconds, but reconcile explicitly when
                    // our local timer elapses so app and server state agree instead of drifting. The
                    // remove() guard prevents a double stop when the user stops early.
                    launch {
                        delay(((maxDurationSeconds + 2) * 1000L).milliseconds)
                        if (activeServerRecordings.remove(device.id) != null) {
                            publishActiveIds()
                            val s = repository.getSettings()
                            serverClient.stopRecording(
                                serverUrl = s.serverBaseUrl,
                                apiKey = s.serverApiKey.ifBlank { null },
                                deviceId = device.id
                            )
                        }
                    }
                    return@launch
                } else {
                    Log.w(tag, "Server recording failed, falling back to local recording: ${result.exceptionOrNull()?.message}")
                }
            }

            // Local recording (Default or Fallback)
            startLocalRecording(device, eventType, maxDurationSeconds)
        }
    }

    private fun startLocalRecording(device: DeviceEntity, eventType: EventType, maxDurationSeconds: Int) {
        val timestampStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val recordingsDir = File(context.filesDir, "recordings").apply { mkdirs() }
        val outputFile = File(recordingsDir, "REC_${device.id}_${eventType.name}_$timestampStr.mkv")

        val startTime = System.currentTimeMillis()
        Log.i(tag, "Starting local MKV recording for ${device.name} [${eventType.name}] -> ${outputFile.name}")

        val firstFrameRef = AtomicReference<ByteArray?>(null)
        val job = scope.launch {
            try {
                // Recording capture loop: captures snapshot frames / MJPEG stream chunks into crash-safe MKV
                recordStreamToMkv(device, outputFile, maxDurationSeconds, firstFrameRef)
            } catch (_: CancellationException) {
                Log.d(tag, "Recording cancelled/stopped normally for ${device.name}")
            } catch (e: Exception) {
                Log.e(tag, "Error during recording for ${device.name}", e)
            } finally {
                // Finalization performs suspending DB writes (recording row, thumbnail, auto-export
                // mirror). On a manual early stop this coroutine is already cancelled, which would
                // abort those suspends and silently lose the whole recording — run it NonCancellable
                // so stopping early still commits the recording like a natural timeout does.
                withContext(NonCancellable) {
                    finalizeRecording(device, eventType, startTime, outputFile, firstFrameRef.get())
                }
            }
        }

        activeRecordings[device.id] = ActiveRecordingJob(
            deviceId = device.id,
            eventType = eventType,
            startTimeMs = startTime,
            outputFile = outputFile,
            job = job
        )
        publishActiveIds()
    }

    /**
     * Stops an ongoing recording and commits it to database or informs the server
     */
    @Synchronized
    fun stopRecording(deviceId: Long) {
        val active = activeRecordings.remove(deviceId)
        if (active != null) {
            Log.i(tag, "Stopping active local recording for device $deviceId")
            publishActiveIds()
            active.job.cancel()
        }

        if (activeServerRecordings.remove(deviceId) != null) {
            Log.i(tag, "Stopping active server recording for device $deviceId")
            publishActiveIds()
            scope.launch {
                val settings = repository.getSettings()
                serverClient.stopRecording(
                    serverUrl = settings.serverBaseUrl,
                    apiKey = settings.serverApiKey.ifBlank { null },
                    deviceId = deviceId
                )
            }
        }
    }

    /**
     * Records incoming snapshot or MJPEG frames directly into a streaming Matroska (MKV) container
     * as a `V_MJPEG` track. Every cluster is flushed incrementally, so the file is crash-resilient.
     *
     * Deliberately NO re-encoding during capture (keeps the 24/7 monitor cheap on CPU/battery).
     * In-app playback uses a dedicated JPEG frame player (JpegFramePlayer), and an H.264
     * transcode is produced lazily only when the user exports/shares (see [ExportTranscoder]).
     */
    private suspend fun recordStreamToMkv(
        device: DeviceEntity,
        outputFile: File,
        maxDurationSeconds: Int,
        firstFrameRef: AtomicReference<ByteArray?>
    ) = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + (maxDurationSeconds * 1000L)
        val startTime = System.currentTimeMillis()
        val fps = device.snapshotFps.coerceIn(1, 30)

        MkvStreamMuxer(outputFile).use { muxer ->
            fun handleJpeg(jpeg: ByteArray) {
                if (firstFrameRef.get() == null) firstFrameRef.set(jpeg)
                val relTimeMs = System.currentTimeMillis() - startTime
                muxer.writeMjpegFrame(jpeg, relTimeMs)
            }

            if (device.streamProtocol == StreamProtocol.MJPEG_STREAM) {
                val mjpegReader = MjpegStreamReader()
                try {
                    mjpegReader.streamRawJpeg(device).collect { jpegBytes ->
                        if (!isActive || System.currentTimeMillis() >= deadline) return@collect
                        handleJpeg(jpegBytes)
                    }
                } catch (_: Exception) {
                    // Fall back to snapshot polling if MJPEG stream drops
                }
            }

            // Fallback or snapshot polling mode
            while (isActive && System.currentTimeMillis() < deadline) {
                try {
                    val frameBytes = HttpSnapshotClient.fetchSnapshotBytes(device)
                    if (frameBytes != null && frameBytes.isNotEmpty()) handleJpeg(frameBytes)
                } catch (_: Exception) {
                    // Ignore single frame fetch glitches
                }
                delay((1000L / fps).milliseconds)
            }
        }
    }

    private suspend fun finalizeRecording(
        device: DeviceEntity,
        eventType: EventType,
        startTimeMs: Long,
        outputFile: File,
        firstFrameJpeg: ByteArray?
    ) {
        activeRecordings.remove(device.id)
        publishActiveIds()
        val durationSec = ((System.currentTimeMillis() - startTimeMs) / 1000L).coerceAtLeast(1L)
        val fileSize = if (outputFile.exists()) outputFile.length() else 0L

        if (fileSize > 0) {
            // Prefer the captured JPEG frame for the thumbnail: MJPEG-in-MKV cannot be decoded by
            // MediaMetadataRetriever on most devices, so frame-based thumbnails are far more reliable.
            val thumbPath = firstFrameJpeg?.let {
                ThumbnailUtil.saveThumbnailFromJpeg(context, it, device.id)
            } ?: ThumbnailUtil.extractAndSaveThumbnail(context, outputFile.absolutePath, device.id)
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

            // Opt-in archive mirror: copy the finalized (lossless) MJPEG MKV into the user's SAF
            // export folder so sync tools always see complete, up-to-date recordings. Deliberately
            // done only after finalization — a live-appended file in a synced folder would churn
            // constantly and expose half-written recordings. Best-effort: failures are logged, never
            // fatal (the authoritative copy always stays in the app's private storage).
            val settings = repository.getSettings()
            if (settings.autoExportOnFinalize && settings.exportFolderUri.isNotBlank()) {
                val mirrored = try {
                    ExportHelper.saveFileToSafFolder(
                        context, settings.exportFolderUri.toUri(), outputFile, outputFile.name
                    )
                } catch (e: Exception) {
                    Log.e(tag, "Auto-export mirror failed for ${outputFile.name}", e)
                    false
                }
                if (!mirrored) {
                    Log.w(tag, "Auto-export to folder did not complete for ${outputFile.name} " +
                            "(folder grant broken or storage full?)")
                }
            }
        } else {
            Log.w(tag, "Recording produced empty file, discarding ${outputFile.name}")
            if (outputFile.exists()) outputFile.delete()
        }
    }
}
