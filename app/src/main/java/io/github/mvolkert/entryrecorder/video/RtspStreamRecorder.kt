package io.github.mvolkert.entryrecorder.video

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.data.local.entity.RecordingEntity
import io.github.mvolkert.entryrecorder.data.model.ConnectionCapability
import io.github.mvolkert.entryrecorder.data.model.ConnectionQuality
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.data.model.RecordingMode
import io.github.mvolkert.entryrecorder.data.model.StreamProtocol
import io.github.mvolkert.entryrecorder.data.network.HttpSnapshotClient
import io.github.mvolkert.entryrecorder.data.network.MjpegStreamReader
import io.github.mvolkert.entryrecorder.data.repository.IntercomRepository
import io.github.mvolkert.entryrecorder.data.server.ServerRecordingClient
import io.github.mvolkert.entryrecorder.domain.device.IntercomEvent
import io.github.mvolkert.entryrecorder.util.ExportHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
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

/**
 * Things the capture loop learns that no caller asked for, published so the monitor service can act on
 * them: a snapshot endpoint that goes quiet has to reach the device's connection status and the live
 * card, not only logcat.
 */
sealed interface RecorderEvent {
    data class Connection(val event: IntercomEvent.ConnectionState) : RecorderEvent
}

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
    private val activeServerRecordings = ConcurrentHashMap<Long, ActiveServerRecording>()

    // Observable recording status: the plain ConcurrentHashMaps above are invisible to Compose, so
    // every mutation also publishes the affected device ids here (local + server recordings). The
    // Live screen observes this StateFlow through LiveViewModel instead of polling isRecording().
    private val _activeDeviceIds = MutableStateFlow<Set<Long>>(emptySet())
    /** Device ids that currently have an active recording (local or on the server). */
    val activeDeviceIds: StateFlow<Set<Long>> = _activeDeviceIds.asStateFlow()

    private val _events = Channel<RecorderEvent>(Channel.BUFFERED)
    /** One-shot recorder observations for the monitor service to route (never replayed to a late collector). */
    val events = _events.receiveAsFlow()

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

    /**
     * The app's view of a recording the server is running. Kept deliberately thin: the server owns the job
     * and its duration, so what the phone has to remember is which trigger started it (for the stop guard),
     * how long it asked for (for the reconcile deadline) and whether the job is even this client's to stop.
     */
    private data class ActiveServerRecording(
        val eventType: EventType,
        val maxDurationSeconds: Int,
        val startedByThisRequest: Boolean,
        /** Server row id, once the server returns one from start (Phase S). Null with today's server. */
        val recordingId: Long?
    )

    fun isRecording(deviceId: Long): Boolean = deviceId in _activeDeviceIds.value

    /**
     * True only while the *phone's* capture loop polls the device's snapshot endpoint (not for
     * server-side recordings). Motion analysis backs off on this, because in PYTHON_SERVER mode the
     * server is the poller and slowing the analyzer would relieve contention that doesn't exist here.
     */
    fun isLocallyRecording(deviceId: Long): Boolean = activeRecordings.containsKey(deviceId)

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
                    val start = result.getOrThrow()
                    activeServerRecordings[device.id] = ActiveServerRecording(
                        eventType = eventType,
                        maxDurationSeconds = maxDurationSeconds,
                        startedByThisRequest = start.startedByThisRequest,
                        recordingId = start.recordingId
                    )
                    publishActiveIds()
                    if (!start.startedByThisRequest) {
                        Log.i(tag, "Server was already recording ${device.name}; watching that job instead of arming a stop for it")
                    }
                    // Reconcile against the server's own job list rather than trusting a blind local timer:
                    // the job can finish early, be stopped from the server's web UI, or outlive what this
                    // request asked for, and in every one of those cases the phone has to stop claiming it.
                    launch { watchServerRecording(device) }
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

        val serverRecording = activeServerRecordings[deviceId]
        when {
            serverRecording == null -> Unit
            reason != null && serverRecording.eventType != reason ->
                Log.i(tag, "Ignoring $reason post-record stop for device $deviceId: server recording is ${serverRecording.eventType}")
            else -> {
                Log.i(tag, "Stopping active server recording for device $deviceId")
                activeServerRecordings.remove(deviceId)
                publishActiveIds()
                scope.launch {
                    val settings = repository.getSettings()
                    requestServerStop(deviceId, "device $deviceId", settings.serverBaseUrl, settings.serverApiKey.ifBlank { null })
                }
            }
        }
    }

    /**
     * Keeps the app's view of a server recording in step with the server's own job list.
     *
     * The old behaviour was a `maxDurationSeconds + 2` sleep and an unconditional stop, which drifted in both
     * directions: the phone kept showing REC after the server finalized, and it sent a stop for a job that
     * belongs to an earlier request (`already_recording`) whose duration was never this one's.
     */
    private suspend fun watchServerRecording(device: DeviceEntity) {
        val tracked = activeServerRecordings[device.id] ?: return
        val stopAtMs = System.currentTimeMillis() + (tracked.maxDurationSeconds * 1000L) + SERVER_STOP_GRACE_MS
        var failedProbes = 0

        while (currentCoroutineContext().isActive) {
            delay(SERVER_RECONCILE_INTERVAL_MS.milliseconds)
            // Cleared by a stop through the app or by a new trigger: this coroutine no longer owns anything.
            val active = activeServerRecordings[device.id] ?: return
            val settings = repository.getSettings()
            val apiKey = settings.serverApiKey.ifBlank { null }
            val probe = serverClient.activeRecordingFor(settings.serverBaseUrl, apiKey, device.id)
            val job = probe.getOrNull()

            when {
                probe.isFailure -> {
                    failedProbes++
                    Log.w(
                        tag,
                        "Server job for ${device.name} not reconcilable (attempt $failedProbes): " +
                            probe.exceptionOrNull()?.message
                    )
                    if (failedProbes >= SERVER_RECONCILE_GIVE_UP_PROBES) {
                        if (active.startedByThisRequest) {
                            requestServerStop(device.id, device.name, settings.serverBaseUrl, apiKey)
                        }
                        clearServerRecording(device.id, "status probes stopped working")
                        return
                    }
                }

                // Nothing running on the server anymore: it finalized on its own or was stopped in its web UI.
                job == null -> {
                    clearServerRecording(device.id, "the server no longer has a job for it")
                    return
                }

                System.currentTimeMillis() >= stopAtMs -> {
                    if (active.startedByThisRequest) {
                        Log.i(
                            tag,
                            "Server job on ${device.name} still live after ${tracked.maxDurationSeconds}s " +
                                "(elapsed ${job.elapsedSeconds}s of ${job.maxDurationSeconds}s), asking it to stop"
                        )
                        requestServerStop(device.id, device.name, settings.serverBaseUrl, apiKey)
                        clearServerRecording(device.id, "stopped past the requested duration")
                        return
                    }
                    // Not this request's job to end: it carries its own duration, so keep watching until the
                    // server drops it instead of truncating someone else's recording.
                    Log.i(tag, "Server job on ${device.name} outlived the requested duration; leaving it running")
                }
            }
        }
    }

    /** Drops the app's view of a server recording; the job on the server itself is untouched. */
    private fun clearServerRecording(deviceId: Long, reason: String) {
        if (activeServerRecordings.remove(deviceId) != null) {
            publishActiveIds()
            Log.i(tag, "No longer tracking the server recording of device $deviceId ($reason)")
        }
    }

    private suspend fun requestServerStop(deviceId: Long, label: String, serverUrl: String, apiKey: String?) {
        val outcome = serverClient.stopRecording(serverUrl, apiKey, deviceId)
        val answer = outcome.getOrNull()
        when {
            answer == null -> Log.w(tag, "Server stop for $label failed: ${outcome.exceptionOrNull()?.message}")
            answer.hadActiveJob -> Log.i(tag, "Server stopped the recording of $label")
            // The job had already ended on its own: exactly the drift the reconcile loop exists to catch.
            else -> Log.i(tag, "Server had no job left to stop for $label (it had already ended)")
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
        val fps = device.effectiveSnapshotFps

        MkvStreamMuxer(outputFile).use { muxer ->
            fun handleJpeg(jpeg: ByteArray, atMs: Long) {
                muxer.writeMjpegFrame(jpeg, (atMs - baselineMs).coerceAtLeast(0L))
            }

            // Prepend the frames captured just before the trigger; baselineMs is the earliest of them,
            // so their relative times are >= 0 and stay monotonic against the live frames that follow.
            // firstFrameRef deliberately only picks up the first LIVE frame: the oldest pre-roll frame is
            // typically the empty approach scene, which would make every thumbnail look identical.
            preRoll.forEach { handleJpeg(it.jpeg, it.timestampMs) }

            fun handleLiveFrame(jpeg: ByteArray, atMs: Long) {
                if (firstFrameRef.get() == null) firstFrameRef.set(jpeg)
                handleJpeg(jpeg, atMs)
            }

            if (device.streamProtocol == StreamProtocol.MJPEG_STREAM) {
                val mjpegReader = MjpegStreamReader()
                try {
                    mjpegReader.streamRawJpeg(device).collect { jpegBytes ->
                        if (!isActive || System.currentTimeMillis() >= deadline) return@collect
                        handleLiveFrame(jpegBytes, System.currentTimeMillis())
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
            // Edge-triggered health: one announcement per state change instead of one per dropped frame,
            // so a recording that never gets a frame is not confused with a short quiet one.
            var reportedQuality = HttpSnapshotClient.snapshotQuality(device.id)
            var framesWritten = 0
            while (isActive && System.currentTimeMillis() < deadline) {
                nextFrameAt += frameIntervalMs
                val frameBytes = HttpSnapshotClient.fetchSnapshotBytes(device)
                if (frameBytes != null && frameBytes.isNotEmpty()) {
                    handleLiveFrame(frameBytes, System.currentTimeMillis())
                    framesWritten++
                }
                val quality = HttpSnapshotClient.snapshotQuality(device.id)
                if (quality != reportedQuality) {
                    reportedQuality = quality
                    val failures = HttpSnapshotClient.consecutiveFailures(device.id)
                    Log.w(tag, "Snapshot endpoint for ${device.name} is $quality ($failures polls in a row without a frame)")
                    _events.trySend(
                        RecorderEvent.Connection(
                            IntercomEvent.ConnectionState(
                                device = device,
                                quality = quality,
                                capability = ConnectionCapability.SNAPSHOT,
                                message = if (quality == ConnectionQuality.ONLINE)
                                    "Snapshot frames recovered after $failures missed polls"
                                else "Snapshot endpoint delivered no frame for $failures polls in a row"
                            )
                        )
                    )
                }
                val remainingMs = nextFrameAt - System.currentTimeMillis()
                if (remainingMs > 0) delay(remainingMs.milliseconds)
            }
            // The rate the loop actually got frames at, versus what was configured: the gap is what the
            // endpoint's serial ceiling costs, and it has to be visible without a LAN measurement.
            val elapsedSec = ((System.currentTimeMillis() - baselineMs) / 1000f).coerceAtLeast(1f)
            Log.i(
                tag,
                "Recorded ${device.name} at %.1f fps (configured %d fps, endpoint ceiling %d fps) "
                    .format(framesWritten / elapsedSec, fps, device.maxSnapshotFps) +
                    "${framesWritten} frames"
            )
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

    companion object {
        /** How often a server recording is checked against the server's own list of running jobs. */
        private const val SERVER_RECONCILE_INTERVAL_MS = 10_000L

        /** Extra time past the requested duration before the app asks the server to end the job. */
        private const val SERVER_STOP_GRACE_MS = 2_000L

        /** Status probes in a row that have to fail before the app stops tracking the job. */
        private const val SERVER_RECONCILE_GIVE_UP_PROBES = 3
    }
}
