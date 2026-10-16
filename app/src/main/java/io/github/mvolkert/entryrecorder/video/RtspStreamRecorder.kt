package io.github.mvolkert.entryrecorder.video

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.data.model.RecordingMode
import io.github.mvolkert.entryrecorder.data.network.HttpSnapshotClient
import io.github.mvolkert.entryrecorder.data.network.MjpegStreamReader
import io.github.mvolkert.entryrecorder.data.repository.IntercomRepository
import io.github.mvolkert.entryrecorder.data.server.ServerClient
import io.github.mvolkert.entryrecorder.di.IoDispatcher
import io.github.mvolkert.entryrecorder.domain.model.Device
import io.github.mvolkert.entryrecorder.util.ExportGateway
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Things the capture loop learns that no caller asked for, published so the monitor service can act on
 * them: a snapshot endpoint that goes quiet has to reach the device's connection status and the live
 * card, not only logcat.
 */
sealed interface RecorderEvent {
    data class Connection(val event: io.github.mvolkert.entryrecorder.domain.device.IntercomEvent.ConnectionState) : RecorderEvent
}

/**
 * The recording controller the monitor service and Live view code against. It is deliberately thin: it owns
 * the observable state (which devices are recording, local or server) and the one-shot event channel, decides
 * between the two capture back ends from the stored [RecordingMode], then delegates. The phone-side frame
 * loop lives in [LocalCaptureLoop]; the Python-server job tracking lives in [ServerRecordingOrchestrator].
 * Splitting them keeps each mechanism (muxer + folding triggers vs reconcile + resume) readable and testable
 * on its own while [RecordingController] stays the single seam fakes implement.
 */
@OptIn(UnstableApi::class)
@Singleton
class RtspStreamRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: IntercomRepository,
    private val serverClient: ServerClient,
    private val snapshotClient: HttpSnapshotClient,
    private val mjpegReader: MjpegStreamReader,
    private val exportGateway: ExportGateway,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : RecordingController {
    private val tag = "RtspStreamRecorder"
    private val scope = CoroutineScope(ioDispatcher + SupervisorJob())

    // Observable recording status: the maps inside the two collaborators are invisible to Compose, so every
    // mutation republishes the affected device ids here (local + server recordings). The Live screen observes
    // this StateFlow through LiveViewModel instead of polling for a live status.
    private val _activeDeviceIds = MutableStateFlow<Set<Long>>(emptySet())
    /** Device ids that currently have an active recording (local or on the server). */
    override val activeDeviceIds: StateFlow<Set<Long>> = _activeDeviceIds.asStateFlow()

    private val _events = Channel<RecorderEvent>(Channel.BUFFERED)
    /** One-shot recorder observations for the monitor service to route (never replayed to a late collector). */
    override val events: Flow<RecorderEvent> = _events.receiveAsFlow()

    private val localLoop = LocalCaptureLoop(
        context = context,
        repository = repository,
        snapshotClient = snapshotClient,
        mjpegReader = mjpegReader,
        exportGateway = exportGateway,
        ioDispatcher = ioDispatcher,
        events = _events,
        onActiveChanged = ::publishActiveIds,
    )

    private val serverOrchestrator = ServerRecordingOrchestrator(
        repository = repository,
        serverClient = serverClient,
        ioDispatcher = ioDispatcher,
        onActiveChanged = ::publishActiveIds,
    )

    private fun publishActiveIds() {
        _activeDeviceIds.value = localLoop.activeDeviceIds + serverOrchestrator.activeDeviceIds
    }

    /**
     * True only while the *phone's* capture loop polls the device's snapshot endpoint (not for
     * server-side recordings). Motion analysis backs off on this, because in PYTHON_SERVER mode the
     * server is the poller and slowing the analyzer would relieve contention that doesn't exist here.
     */
    override fun isLocallyRecording(deviceId: Long): Boolean = localLoop.isRecording(deviceId)

    /**
     * Start recording for a given device and trigger event, choosing the Python server or the local capture
     * loop from the stored mode (local is the default and the fallback).
     *
     * A clip that is already running on this device is never replaced: the new trigger is folded into it,
     * because one recording per device is what the camera and the storage budget allow. A server job the app
     * already tracks carries its own trigger and length with no fold API, so a second event simply adds nothing.
     */
    @Synchronized
    override fun startRecording(
        device: Device,
        eventType: EventType,
        maxDurationSeconds: Int,
        preRoll: List<PreRollFrame>,
    ) {
        if (localLoop.isRecording(device.id)) {
            // Fold into the running clip; the loop re-detects that one is active and folds rather than starting a second.
            localLoop.start(device, eventType, maxDurationSeconds, preRoll)
            return
        }
        if (serverOrchestrator.isActive(device.id)) {
            // The server owns that job's trigger and length, and its API offers no way to add a second tag to
            // a running recording, so there is nothing to fold into.
            Log.i(tag, "Device ${device.id} is recording on the server; a $eventType event adds no second job")
            return
        }

        scope.launch {
            val settings = repository.getSettings()
            if (settings.recordingMode == RecordingMode.PYTHON_SERVER) {
                val outcome = serverOrchestrator.start(device, eventType, maxDurationSeconds, settings)
                if (outcome is ServerStartOutcome.FallbackToLocal) {
                    localLoop.start(device, eventType, maxDurationSeconds, preRoll)
                }
                return@launch
            }
            localLoop.start(device, eventType, maxDurationSeconds, preRoll)
        }
    }

    /**
     * Stops an ongoing recording and commits it to database or informs the server.
     *
     * [reason] restricts the stop to the recording that this event type started: the motion and noise
     * post-record buffers stop on a delay, and without the guard a short noise clip ending at 20 s
     * would cut a 60 s doorbell recording on the same device short. A null reason (the manual stop from
     * the Live view) stops whatever is running for the device. Both back ends are consulted, since a
     * device only ever has one live job but the caller does not know which end owns it.
     */
    @Synchronized
    override fun stopRecording(deviceId: Long, reason: EventType?) {
        localLoop.stop(deviceId, reason)
        serverOrchestrator.stop(deviceId, reason)
    }

    /**
     * Rebuilds the app's view of server recordings that were in flight when the process died, so a reboot
     * still shows REC, reconciles against the server and auto-stops a job this client started. Delegated to
     * the orchestrator, which owns that tracking.
     */
    override suspend fun resumePersistedServerRecordings(devices: List<Device>) {
        serverOrchestrator.resume(devices)
    }
}
