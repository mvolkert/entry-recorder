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

/** A captured JPEG plus its wall-clock capture time, used to prepend a motion pre-roll to a recording. */
class PreRollFrame(val timestampMs: Long, val jpeg: ByteArray)

@OptIn(UnstableApi::class)
class RtspStreamRecorder(
    private val context: Context,
    private val repository: IntercomRepository,
    private val serverClient: ServerRecordingClient = ServerRecordingClient()
) {
    private val tag = "RtspStreamRecorder"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeRecordings = ConcurrentHashMap<Long, ActiveRecordingJob>()
    // Server-side recordings remember their trigger type too, so a delayed post-record stop from one
    // event type cannot terminate the recording another event type started (see stopRecording).
    private val activeServerRecordings = ConcurrentHashMap<Long, EventType>()

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
    fun startRecording(
        device: DeviceEntity,
        eventType: EventType,
        maxDurationSeconds: Int = 60,
        preRoll: List<PreRollFrame> = emptyList()
    ) {
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
                    activeServerRecordings[device.id] = eventType
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
            startLocalRecording(device, eventType, maxDurationSeconds, preRoll)
        }
    }

    private fun startLocalRecording(
        device: DeviceEntity,
        eventType: EventType,
        maxDurationSeconds: Int,
        preRoll: List<PreRollFrame>
    ) {
        val timestampStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val recordingsDir = File(context.filesDir, "recordings").apply { mkdirs() }
        val outputFile = File(recordingsDir, "REC_${device.id}_${eventType.name}_$timestampStr.mkv")

        // Start the timeline at the earliest pre-roll frame so the recording's timestamp/duration cover
        // the moment that triggered motion, not just what happened after detection confirmed it.
        val startTime = preRoll.firstOrNull()?.timestampMs ?: System.currentTimeMillis()
        Log.i(tag, "Starting local MKV recording for ${device.name} [${eventType.name}] -> ${outputFile.name}")

        val firstFrameRef = AtomicReference<ByteArray?>(null)
        val job = scope.launch {
            try {
                // Recording capture loop: captures snapshot frames / MJPEG stream chunks into crash-safe MKV
                recordStreamToMkv(device, outputFile, maxDurationSeconds, firstFrameRef, startTime, preRoll)
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
     * Stops an ongoing recording and commits it to database or informs the server.
     *
     * [reason] restricts the stop to the recording that this event type started: the motion and noise
     * post-record buffers stop on a delay, and without the guard a short noise clip ending at 20 s
     * would cut a 60 s doorbell recording on the same device short. A null reason (the manual stop from
     * the Live view) stops whatever is running for the device.
     */
    @Synchronized
    fun stopRecording(deviceId: Long, reason: EventType? = null) {
        val active = activeRecordings[deviceId]
        when {
            active == null -> Unit
            reason != null && active.eventType != reason ->
                Log.i(tag, "Ignoring $reason post-record stop for device $deviceId: active recording is ${active.eventType}")
            else -> {
                Log.i(tag, "Stopping active local recording for device $deviceId")
                activeRecordings.remove(deviceId)
                publishActiveIds()
                active.job.cancel()
            }
        }

        val serverEventType = activeServerRecordings[deviceId]
        when {
            serverEventType == null -> Unit
            reason != null && serverEventType != reason ->
                Log.i(tag, "Ignoring $reason post-record stop for device $deviceId: server recording is $serverEventType")
            else -> {
                Log.i(tag, "Stopping active server recording for device $deviceId")
                activeServerRecordings.remove(deviceId)
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
        firstFrameRef: AtomicReference<ByteArray?>,
        baselineMs: Long,
        preRoll: List<PreRollFrame>
    ) = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + (maxDurationSeconds * 1000L)
        val fps = device.snapshotFps.coerceIn(1, 30)

        MkvStreamMuxer(outputFile).use { muxer ->
            fun handleJpeg(jpeg: ByteArray, atMs: Long) {
                if (firstFrameRef.get() == null) firstFrameRef.set(jpeg)
                muxer.writeMjpegFrame(jpeg, (atMs - baselineMs).coerceAtLeast(0L))
            }

            // Prepend the frames captured just before the trigger; baselineMs is the earliest of them,
            // so their relative times are >= 0 and stay monotonic against the live frames that follow.
            preRoll.forEach { handleJpeg(it.jpeg, it.timestampMs) }

            if (device.streamProtocol == StreamProtocol.MJPEG_STREAM) {
                val mjpegReader = MjpegStreamReader()
                try {
                    mjpegReader.streamRawJpeg(device).collect { jpegBytes ->
                        if (!isActive || System.currentTimeMillis() >= deadline) return@collect
                        handleJpeg(jpegBytes, System.currentTimeMillis())
                    }
                } catch (e: Exception) {
                    // Snapshot polling continues below. Not silent: a stream that dies after a few frames
                    // otherwise looks identical to a recording that simply has no motion in it.
                    Log.w(tag, "MJPEG stream for ${device.name} ended (${e.message}), continuing with snapshot polling")
                }
            }

            // Snapshot polling mode. Pace against a rolling deadline: a grab already costs a network
            // round trip plus a JPEG write, so sleeping a full frame interval *after* each capture
            // records slower than device.snapshotFps and spaces the motion-analysis comparisons
            // further apart than the configured rate implies.
            val frameIntervalMs = 1000L / fps
            var nextFrameAt = System.currentTimeMillis()
            var endpointHealthy = true
            while (isActive && System.currentTimeMillis() < deadline) {
                nextFrameAt += frameIntervalMs
                try {
                    val frameBytes = HttpSnapshotClient.fetchSnapshotBytes(device)
                    if (frameBytes != null && frameBytes.isNotEmpty()) {
                        handleJpeg(frameBytes, System.currentTimeMillis())
                        if (!endpointHealthy) {
                            Log.i(tag, "Snapshot frames from ${device.name} recovered after a gap")
                            endpointHealthy = true
                        }
                    } else if (endpointHealthy) {
                        // Announce the gap once instead of once per frame: a recording that never gets
                        // a frame is otherwise indistinguishable from a short quiet one.
                        Log.w(tag, "Snapshot endpoint for ${device.name} returned no image, recording continues without frames for now")
                        endpointHealthy = false
                    }
                } catch (e: Exception) {
                    if (endpointHealthy) {
                        Log.w(tag, "Snapshot grab threw for ${device.name}: ${e.message}, recording continues without frames for now")
                        endpointHealthy = false
                    }
                }
                val remainingMs = nextFrameAt - System.currentTimeMillis()
                if (remainingMs > 0) delay(remainingMs.milliseconds)
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
