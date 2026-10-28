package io.github.mvolkert.entryrecorder.video

import android.content.Context
import timber.log.Timber
import io.github.mvolkert.entryrecorder.data.model.ConnectionCapability
import io.github.mvolkert.entryrecorder.data.model.ConnectionQuality
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.data.model.StreamProtocol
import io.github.mvolkert.entryrecorder.data.network.HttpSnapshotClient
import io.github.mvolkert.entryrecorder.data.network.MjpegStreamReader
import io.github.mvolkert.entryrecorder.data.repository.IntercomRepository
import io.github.mvolkert.entryrecorder.di.IoDispatcher
import io.github.mvolkert.entryrecorder.domain.device.IntercomEvent
import io.github.mvolkert.entryrecorder.domain.model.Device
import io.github.mvolkert.entryrecorder.domain.model.Recording
import io.github.mvolkert.entryrecorder.domain.triggers.TriggerCoordinator
import io.github.mvolkert.entryrecorder.util.ExportGateway
import io.github.mvolkert.entryrecorder.util.ExportFailureMonitor
import io.github.mvolkert.entryrecorder.util.recordingsDirOf
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Which trigger a clip is filed under when more than one happened while it was running. A doorbell outranks
 * the rest because it is the event the user goes looking for afterwards; a manual clip outranks nothing because
 * the user knows they pressed the button.
 */
internal val EventType.significance: Int
    get() = when (this) {
        EventType.RING -> 3
        EventType.MOTION -> 2
        EventType.NOISE -> 1
        EventType.MANUAL -> 0
        EventType.UNKNOWN -> 0
    }

/** Sentinel deadline for a trigger with no configured recording ceiling. */
internal const val NO_RECORDING_CEILING_MS = Long.MAX_VALUE

/**
 * The deadline after folding a new trigger in: never sooner than the clip already runs to, and never past
 * the trigger's hard [ceilingMs]. A clip that has somehow outlived its ceiling is left alone rather than
 * shortened under the running muxer.
 */
internal fun cappedDeadlineMs(currentMs: Long, wantedMs: Long, ceilingMs: Long): Long =
    maxOf(currentMs, minOf(wantedMs, ceilingMs))

/** True once a clip has written its frame ceiling; [maxFrames] 0 means the trigger has no frame limit. */
internal fun frameCapReached(maxFrames: Int, framesWritten: Int): Boolean =
    maxFrames > 0 && framesWritten >= maxFrames

/**
 * The phone-side capture loop: it polls the device's snapshot / MJPEG endpoint, muxes frames into a
 * crash-safe MKV, writes the thumbnail and the recording row on finalize, and mirrors to the export folder
 * when auto-export is on. One clip per device — a second trigger folds into the running clip instead of
 * stopping it. [RtspStreamRecorder] decides whether a recording is local at all; everything about actually
 * capturing it lives here.
 *
 * @param onActiveChanged invoked after every add/remove from [activeRecordings] so the controller can
 * republish the merged local + server device-id StateFlow it exposes.
 * @param events the controller's one-shot channel; snapshot-endpoint health is announced here.
 */
internal class LocalCaptureLoop(
    private val context: Context,
    private val repository: IntercomRepository,
    private val snapshotClient: HttpSnapshotClient,
    private val mjpegReader: MjpegStreamReader,
    private val exportGateway: ExportGateway,
    private val exportFailureMonitor: ExportFailureMonitor,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val events: Channel<RecorderEvent>,
    private val onActiveChanged: () -> Unit,
    /**
     * Source for the RTSP H.264 passthrough path (change `rtsp-h264-passthrough`). Null in the shipped
     * deployment: the decode-free Media3 RTSP tap that would fill it is unverifiable without a physical
     * camera and stays the deferred on-device task, so the snapshot/MJPEG path below remains authoritative.
     */
    private val h264Source: H264SampleSource? = null,
) {
    private val tag = "RtspStreamRecorder"
    private val scope = CoroutineScope(ioDispatcher + SupervisorJob())
    private val activeRecordings = ConcurrentHashMap<Long, ActiveRecordingJob>()

    /** Device ids the phone is currently writing a clip for. */
    val activeDeviceIds: Set<Long> get() = activeRecordings.keys.toSet()

    fun isRecording(deviceId: Long): Boolean = activeRecordings.containsKey(deviceId)

    /**
     * Start (or fold into) a local clip. A clip already running on this device is never replaced: the new
     * trigger folds into it, because one recording per device is what the camera and the storage budget allow.
     */
    fun start(
        device: Device,
        eventType: EventType,
        maxDurationSeconds: Int,
        preRoll: List<PreRollFrame>,
    ) {
        val running = activeRecordings[device.id]
        if (running != null) {
            foldIntoRunningClip(running, device, eventType, maxDurationSeconds)
            return
        }
        startLocalRecording(device, eventType, maxDurationSeconds, preRoll)
    }

    /**
     * Stops the running local clip on [deviceId] when [reason] matches (or is null). Returns whether a stop
     * was issued; a `reason` that doesn't match the clip's primary trigger is a stale post-record timer and
     * is ignored so it cannot truncate a longer recording on the same device.
     */
    fun stop(deviceId: Long, reason: EventType?): Boolean {
        val active = activeRecordings[deviceId] ?: return false
        if (reason != null && active.triggers.primary != reason) {
            Timber.tag(tag).i("Ignoring $reason post-record stop for device $deviceId: active recording is ${active.triggers.primary.name}")
            return false
        }
        Timber.tag(tag).i("Stopping active local recording for device $deviceId")
        activeRecordings.remove(deviceId)
        onActiveChanged()
        active.job.cancel()
        return true
    }

    /**
     * A clip the phone is still writing. [triggers] and [deadlineMs] are mutable because a second trigger
     * folds into the running clip rather than replacing it, and the capture loop reads how long it has left
     * straight from [deadlineMs] so that fold can push the end out. [ceilingDeadlineMs] is where the
     * trigger's hard recording limit falls, measured from the clip start, so a fold can never push the end
     * past it.
     */
    private class ActiveRecordingJob(
        val deviceId: Long,
        val startTimeMs: Long,
        val outputFile: File,
        val job: Job,
        val triggers: MutableTriggers,
        val deadlineMs: AtomicLong,
        val ceilingDeadlineMs: Long,
    )

    /**
     * The trigger tags of a clip that is still being recorded. One clip carries several because an event that
     * arrives mid-clip is folded into it instead of stopping it (see [start]). [primary] is the tag the
     * recording is filed under: the most significant trigger seen so far, so a doorbell pressed during a
     * motion clip stays findable in the ring filter.
     */
    internal class MutableTriggers(start: EventType) {
        @Volatile
        var primary: EventType = start
            private set

        private val folded = ConcurrentHashMap.newKeySet<EventType>()

        /**
         * Folds [incoming] in. False when the clip was already running under exactly that trigger.
         *
         * Synchronized on the instance: the read-modify-write of [primary] plus the set mutation must not
         * interleave, or two concurrent folds that both outrank the current primary could each demote it and
         * one tag would be lost. Reads of [primary]/[foldedList] stay lock-free (volatile + concurrent set).
         */
        @Synchronized
        fun fold(incoming: EventType): Boolean {
            if (incoming == primary) return false
            if (incoming.significance > primary.significance) {
                folded += primary
                primary = incoming
            } else {
                folded += incoming
            }
            return true
        }

        /** The folded-in triggers, most significant first, as domain event types. */
        fun foldedList(): List<EventType> = folded.sortedByDescending { it.significance }
    }

    /**
     * Folds [eventType] into the clip already running on this device instead of starting a second one. The
     * muxer is mid-file: stopping it to restart would drop the frame in flight plus the reconnect gap, which
     * is exactly what a doorbell pressed during a motion recording used to cost. The clip takes the extra tag
     * and is held open at least [maxDurationSeconds] longer, so the fold covers the event rather than only
     * noting it. The extension stops at the clip's recording ceiling: repeated triggers may not grow one
     * clip past the limit the device configured for the trigger.
     */
    private fun foldIntoRunningClip(
        running: ActiveRecordingJob,
        device: Device,
        eventType: EventType,
        maxDurationSeconds: Int,
    ) {
        if (!running.triggers.fold(eventType)) {
            Timber.tag(tag).d("Device ${device.id} is already recording a $eventType clip")
            return
        }
        val wantedDeadlineMs = System.currentTimeMillis() + maxDurationSeconds * 1000L
        running.deadlineMs.updateAndGet { current ->
            cappedDeadlineMs(current, wantedDeadlineMs, running.ceilingDeadlineMs)
        }
        val secondsLeft = ((running.deadlineMs.get() - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
        Timber.tag(tag).i(
            "Folded $eventType into the running ${running.triggers.primary.name} clip on ${device.name}; " +
                "it now records ${secondsLeft}s more and will be filed as ${running.triggers.primary.name}"
        )
    }

    private fun startLocalRecording(
        device: Device,
        eventType: EventType,
        maxDurationSeconds: Int,
        preRoll: List<PreRollFrame>,
    ) {
        val timestampStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val recordingsDir = recordingsDirOf(context).apply { mkdirs() }
        val outputFile = File(recordingsDir, "REC_${device.id}_${eventType.name}_$timestampStr.mkv")

        // Start the timeline at the earliest pre-roll frame so the recording's timestamp/duration cover
        // the moment that triggered motion, not just what happened after detection confirmed it.
        val startTime = preRoll.firstOrNull()?.timestampMs ?: System.currentTimeMillis()
        Timber.tag(tag).i("Starting local MKV recording for ${device.name} [${eventType.name}] -> ${outputFile.name}")

        val firstFrameRef = AtomicReference<ByteArray?>(null)
        val triggers = MutableTriggers(eventType)
        // The hard recording ceiling, measured from the clip start: it bounds the initial deadline and every
        // later fold, so repeated triggers cannot grow this clip past what the device allows one trigger.
        val ceilingDeadlineMs = TriggerCoordinator.recordCeilingSeconds(device, eventType)
            ?.let { startTime + it * 1000L }
            ?: NO_RECORDING_CEILING_MS
        val maxFrames = device.maxFramesFor(eventType)
        val deadlineMs = AtomicLong(
            minOf(System.currentTimeMillis() + maxDurationSeconds * 1000L, ceilingDeadlineMs)
        )
        val job = scope.launch {
            try {
                // Recording capture loop: captures snapshot frames / MJPEG stream chunks into crash-safe MKV
                recordStreamToMkv(device, outputFile, deadlineMs, firstFrameRef, startTime, preRoll, maxFrames)
            } catch (_: CancellationException) {
                Timber.tag(tag).d("Recording cancelled/stopped normally for ${device.name}")
            } catch (e: Exception) {
                Timber.tag(tag).e(e, "Error during recording for ${device.name}")
            } finally {
                // Finalization performs suspending DB writes (recording row, thumbnail, auto-export
                // mirror). On a manual early stop this coroutine is already cancelled, which would
                // abort those suspends and silently lose the whole recording — run it NonCancellable
                // so stopping early still commits the recording like a natural timeout does.
                withContext(NonCancellable) {
                    finalizeRecording(device, triggers, startTime, outputFile, firstFrameRef.get())
                }
            }
        }

        activeRecordings[device.id] = ActiveRecordingJob(
            deviceId = device.id,
            startTimeMs = startTime,
            outputFile = outputFile,
            job = job,
            triggers = triggers,
            deadlineMs = deadlineMs,
            ceilingDeadlineMs = ceilingDeadlineMs,
        )
        onActiveChanged()
    }

    /**
     * Records incoming snapshot or MJPEG frames directly into a streaming Matroska (MKV) container
     * as a `V_MJPEG` track. Every cluster is flushed incrementally, so the file is crash-resilient.
     *
     * Deliberately NO re-encoding during capture (keeps the 24/7 monitor cheap on CPU/battery).
     * In-app playback uses a dedicated JPEG frame player (JpegFramePlayer), and an H.264
     * transcode is produced lazily only when the user exports/shares (see [ExportTranscoder]).
     *
     * [deadlineMs] is read every frame rather than resolved once, so a trigger folded into this clip while
     * it runs can extend it and actually get recorded. [maxFrames] is the trigger's frame ceiling (0 = no
     * ceiling): the frame-capped paths stop at that exact count rather than at the equivalent duration the
     * deadline encodes, and the H.264 passthrough — which has no frame counter of its own — is bounded by
     * the deadline alone.
     */
    private suspend fun recordStreamToMkv(
        device: Device,
        outputFile: File,
        deadlineMs: AtomicLong,
        firstFrameRef: AtomicReference<ByteArray?>,
        baselineMs: Long,
        preRoll: List<PreRollFrame>,
        maxFrames: Int,
    ) = withContext(ioDispatcher) {
        val fps = device.effectiveSnapshotFps
        // One counter across the MJPEG push and the snapshot polling that can follow it in the same clip, so
        // the frame ceiling is a total for the clip rather than a budget per source.
        val framesWritten = AtomicInteger(0)
        fun atFrameCap(): Boolean = frameCapReached(maxFrames, framesWritten.get())

        MkvStreamMuxer(outputFile).use { muxer ->
            // RTSP H.264 passthrough: the stream is already H.264, so re-mux its access units straight into an
            // H.264 track with no decode and no re-encode. Takes the whole clip: an H.264 track cannot carry the
            // MJPEG pre-roll frames, and a source is only wired when an RTSP camera is actually onboarded.
            val source = h264Source
            if (device.streamProtocol == StreamProtocol.RTSP && source != null) {
                if (preRoll.isNotEmpty()) {
                    Timber.tag(tag).d("Dropping ${preRoll.size} MJPEG pre-roll frames on the ${device.name} H.264 passthrough clip")
                }
                val passthrough = H264PassthroughMuxer(muxer, width = 1280, height = 720)
                try {
                    source.stream(device).collect { unit ->
                        if (!isActive || System.currentTimeMillis() >= deadlineMs.get()) return@collect
                        // firstFrameRef stays null: an H.264 MKV is decodable by MediaMetadataRetriever, so
                        // finalizeRecording extracts the thumbnail from the file instead of a JPEG frame.
                        passthrough.writeUnit(unit, baselineMs)
                    }
                } catch (e: Exception) {
                    Timber.tag(tag).w("RTSP H.264 passthrough for ${device.name} ended (${e.message})")
                }
                return@use
            }

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
                framesWritten.incrementAndGet()
            }

            if (device.streamProtocol == StreamProtocol.MJPEG_STREAM) {
                try {
                    mjpegReader.streamRawJpeg(device).collect { jpegBytes ->
                        if (!isActive || atFrameCap() || System.currentTimeMillis() >= deadlineMs.get()) return@collect
                        handleLiveFrame(jpegBytes, System.currentTimeMillis())
                    }
                } catch (e: Exception) {
                    // Snapshot polling continues below. Not silent: a stream that dies after a few frames
                    // otherwise looks identical to a recording that simply has no motion in it.
                    Timber.tag(tag).w("MJPEG stream for ${device.name} ended (${e.message}), continuing with snapshot polling")
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
            var reportedQuality = snapshotClient.snapshotQuality(device.id)
            while (isActive && !atFrameCap() && System.currentTimeMillis() < deadlineMs.get()) {
                nextFrameAt += frameIntervalMs
                val frameBytes = snapshotClient.fetchSnapshotBytes(device)
                if (frameBytes != null && frameBytes.isNotEmpty()) {
                    handleLiveFrame(frameBytes, System.currentTimeMillis())
                }
                val quality = snapshotClient.snapshotQuality(device.id)
                if (quality != reportedQuality) {
                    reportedQuality = quality
                    val failures = snapshotClient.consecutiveFailures(device.id)
                    // The loss/recovery line itself is logged once, centrally, when this state event reaches
                    // MonitorEventHandler; here we only route the transition so the card and logcat stay in sync.
                    events.trySend(
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
            val frames = framesWritten.get()
            val elapsedSec = ((System.currentTimeMillis() - baselineMs) / 1000f).coerceAtLeast(1f)
            Timber.tag(tag).i(
                "Recorded ${device.name} at %.1f fps (configured %d fps, endpoint ceiling %d fps) "
                    .format(frames / elapsedSec, fps, device.maxSnapshotFps) +
                    "${frames} frames"
            )
            // Worth a line of its own: a clip that stops while the event is still ongoing otherwise looks
            // like the endpoint went quiet.
            if (atFrameCap()) {
                Timber.tag(tag).i("${device.name} clip stopped at its ${maxFrames}-frame recording limit")
            }
        }
    }

    private suspend fun finalizeRecording(
        device: Device,
        triggers: MutableTriggers,
        startTimeMs: Long,
        outputFile: File,
        firstFrameJpeg: ByteArray?,
    ) {
        activeRecordings.remove(device.id)
        onActiveChanged()
        val durationSec = ((System.currentTimeMillis() - startTimeMs) / 1000L).coerceAtLeast(1L)
        val fileSize = if (outputFile.exists()) outputFile.length() else 0L

        if (fileSize > 0) {
            // Prefer the captured JPEG frame for the thumbnail: MJPEG-in-MKV cannot be decoded by
            // MediaMetadataRetriever on most devices, so frame-based thumbnails are far more reliable.
            val thumbPath = firstFrameJpeg?.let {
                ThumbnailUtil.saveThumbnailFromJpeg(context, it, device.id)
            } ?: ThumbnailUtil.extractAndSaveThumbnail(context, outputFile.absolutePath, device.id)
            val recording = Recording(
                deviceId = device.id,
                deviceName = device.name,
                eventType = triggers.primary,
                alsoEventTypes = triggers.foldedList(),
                timestamp = startTimeMs,
                durationSeconds = durationSec,
                filePath = outputFile.absolutePath,
                fileSizeBytes = fileSize,
                thumbnailPath = thumbPath,
            )
            val id = repository.insertRecording(recording)
            Timber.tag(tag).i("Recording saved: id=$id, duration=${durationSec}s, size=$fileSize bytes")

            // Opt-in archive mirror: copy the finalized (lossless) MJPEG MKV into the user's SAF
            // export folder so sync tools always see complete, up-to-date recordings. Deliberately
            // done only after finalization — a live-appended file in a synced folder would churn
            // constantly and expose half-written recordings. Best-effort: failures are logged, never
            // fatal (the authoritative copy always stays in the app's private storage).
            val settings = repository.getSettings()
            if (settings.autoExportOnFinalize && settings.exportFolderUri.isNotBlank()) {
                val mirrored = try {
                    exportGateway.saveFileToSafFolder(settings.exportFolderUri, outputFile, outputFile.name)
                } catch (e: Exception) {
                    Timber.tag(tag).e(e, "Auto-export mirror failed for ${outputFile.name}")
                    false
                }
                if (mirrored) {
                    // A mirror that lands clears any earlier backgrounded failure the user was warned about.
                    exportFailureMonitor.recordSuccess()
                } else {
                    Timber.tag(tag).w("Auto-export to folder did not complete for ${outputFile.name} " +
                            "(folder grant broken or storage full?)")
                    exportFailureMonitor.recordFailure("Auto-export mirror failed for ${outputFile.name}")
                }
            }
        } else {
            Timber.tag(tag).w("Recording produced empty file, discarding ${outputFile.name}")
            if (outputFile.exists()) outputFile.delete()
        }
    }
}
