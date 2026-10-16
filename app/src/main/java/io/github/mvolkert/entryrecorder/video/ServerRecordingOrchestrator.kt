package io.github.mvolkert.entryrecorder.video

import android.util.Log
import io.github.mvolkert.entryrecorder.data.local.entity.ActiveServerRecordingEntity
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.data.repository.IntercomRepository
import io.github.mvolkert.entryrecorder.data.server.ServerClient
import io.github.mvolkert.entryrecorder.di.IoDispatcher
import io.github.mvolkert.entryrecorder.domain.model.AppSettings
import io.github.mvolkert.entryrecorder.domain.model.Device
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * What [ServerRecordingOrchestrator.start] decided, so [RtspStreamRecorder] knows whether a local clip is
 * still owed: either the server took the job, or something (registration, the start call) failed and the
 * phone must record it itself.
 */
internal sealed interface ServerStartOutcome {
    data object Started : ServerStartOutcome
    data object FallbackToLocal : ServerStartOutcome
}

/**
 * The Python-server side of recording: it asks the server to capture, tracks the app's thin view of that
 * job, reconciles against the server's own job list so the phone stops claiming a clip the server finalized
 * (or an earlier request's clip it must not truncate), stops a job past its requested duration, and rebuilds
 * that view after a process restart. The server owns the actual capture; nothing here touches a codec.
 *
 * @param onActiveChanged invoked after every add/remove so the controller republishes the merged id StateFlow.
 */
internal class ServerRecordingOrchestrator(
    private val repository: IntercomRepository,
    private val serverClient: ServerClient,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val onActiveChanged: () -> Unit,
) {
    private val tag = "RtspStreamRecorder"
    private val scope = CoroutineScope(ioDispatcher + SupervisorJob())
    // Server-side recordings remember their trigger type too, so a delayed post-record stop from one
    // event type cannot terminate the recording another event type started (see stop).
    private val activeServerRecordings = ConcurrentHashMap<Long, ActiveServerRecording>()

    /** Device ids the server is currently recording for, as the app tracks them. */
    val activeDeviceIds: Set<Long> get() = activeServerRecordings.keys.toSet()

    fun isActive(deviceId: Long): Boolean = activeServerRecordings.containsKey(deviceId)

    /**
     * The app's view of a recording the server is running. Kept deliberately thin: the server owns the job
     * and its duration, so what the phone has to remember is which trigger started it (for the stop guard),
     * how long it asked for (for the reconcile deadline), whether the job is even this client's to stop, and
     * when it began (so a resume after a process restart honours the time already elapsed).
     */
    private data class ActiveServerRecording(
        val eventType: EventType,
        val maxDurationSeconds: Int,
        val startedByThisRequest: Boolean,
        /** Server row id, once the server returns one from start (Phase S). Null with today's server. */
        val recordingId: Long?,
        /** Wall-clock ms of the successful start; the reconcile deadline is anchored to this. */
        val startedAtMs: Long,
    )

    /**
     * Asks the server to record [device] for [maxDurationSeconds]. Just-in-time registration is the safety net
     * for a device that never synced through Settings. Returns [ServerStartOutcome.Started] (and arms the
     * reconcile watcher) on success; [ServerStartOutcome.FallbackToLocal] when registration or the start call
     * fails, leaving the caller to record locally.
     */
    suspend fun start(
        device: Device,
        eventType: EventType,
        maxDurationSeconds: Int,
        settings: AppSettings,
    ): ServerStartOutcome {
        Log.i(tag, "Initiating server recording on ${settings.serverBaseUrl} for ${device.name}")
        // Just-in-time registration is the safety net for a device that never synced through
        // Settings (created while in APP_LOCAL, or a registration that failed then): without a
        // server id there is nothing to address, so register now and persist the id.
        val serverDeviceId = device.serverDeviceId ?: ensureRegistered(device)
        if (serverDeviceId == null) {
            Log.w(tag, "Could not register ${device.name} on the server; falling back to local recording")
            return ServerStartOutcome.FallbackToLocal
        }
        val serverDevice = if (device.serverDeviceId == null) device.copy(serverDeviceId = serverDeviceId) else device
        val result = serverClient.startRecording(
            serverUrl = settings.serverBaseUrl,
            apiKey = settings.serverApiKey.ifBlank { null },
            serverDeviceId = serverDeviceId,
            deviceName = device.name,
            eventType = eventType,
            durationSeconds = maxDurationSeconds
        )

        if (!result.isSuccess) {
            Log.w(tag, "Server recording failed, falling back to local recording: ${result.exceptionOrNull()?.message}")
            return ServerStartOutcome.FallbackToLocal
        }

        val start = result.getOrThrow()
        val startedAt = System.currentTimeMillis()
        activeServerRecordings[device.id] = ActiveServerRecording(
            eventType = eventType,
            maxDurationSeconds = maxDurationSeconds,
            startedByThisRequest = start.startedByThisRequest,
            recordingId = start.recordingId,
            startedAtMs = startedAt
        )
        onActiveChanged()
        // Persist so a process restart can resume reconciling and auto-stopping this job: the
        // recorder is a process singleton, so the in-memory map alone dies with the process.
        repository.saveActiveServerRecording(
            ActiveServerRecordingEntity(
                deviceId = device.id,
                recordingId = start.recordingId,
                eventType = eventType,
                maxDurationSeconds = maxDurationSeconds,
                startedByThisRequest = start.startedByThisRequest,
                startedAtMs = startedAt
            )
        )
        if (!start.startedByThisRequest) {
            Log.i(tag, "Server was already recording ${device.name}; watching that job instead of arming a stop for it")
        }
        // Reconcile against the server's own job list rather than trusting a blind local timer:
        // the job can finish early, be stopped from the server's web UI, or outlive what this
        // request asked for, and in every one of those cases the phone has to stop claiming it.
        scope.launch { watchServerRecording(serverDevice) }
        return ServerStartOutcome.Started
    }

    /**
     * Stops the tracked server job on [deviceId] when [reason] matches (or is null). Returns whether a stop
     * was issued; a mismatching [reason] is a stale post-record timer and is ignored so it cannot cut a
     * longer server job short.
     */
    fun stop(deviceId: Long, reason: EventType?): Boolean {
        val serverRecording = activeServerRecordings[deviceId] ?: return false
        if (reason != null && serverRecording.eventType != reason) {
            Log.i(tag, "Ignoring $reason post-record stop for device $deviceId: server recording is ${serverRecording.eventType}")
            return false
        }
        Log.i(tag, "Stopping active server recording for device $deviceId")
        activeServerRecordings.remove(deviceId)
        onActiveChanged()
        scope.launch {
            val settings = repository.getSettings()
            repository.deleteActiveServerRecording(deviceId)
            // The stop must address the server job by the server-assigned id, not the local device id.
            val serverDeviceId = repository.getDeviceById(deviceId)?.serverDeviceId
            if (serverDeviceId != null) {
                requestServerStop(serverDeviceId, "device $deviceId", settings.serverBaseUrl, settings.serverApiKey.ifBlank { null })
            } else {
                Log.w(tag, "No server id for device $deviceId; cannot stop its server job")
            }
        }
        return true
    }

    /**
     * Rebuilds the app's view of server recordings that were in flight when the process died, so a reboot
     * still shows REC, reconciles against the server and auto-stops a job this client started. Called from
     * the monitor service once the enabled devices are known. Rows whose device is no longer monitored, or
     * left over while the recording mode is no longer the server, are dropped: the server finalizes on its
     * own, so the only thing lost is the app claiming it. Idempotent — a device already tracked live is
     * skipped, so repeated device-list emissions never stack watchers on one job.
     */
    suspend fun resume(devices: List<Device>) {
        val persisted = repository.getActiveServerRecordings()
        if (persisted.isEmpty()) return
        val recordingMode = repository.getSettings().recordingMode
        val byId = devices.associateBy { it.id }
        for (row in persisted) {
            if (activeServerRecordings.containsKey(row.deviceId)) continue
            val device = byId[row.deviceId]
            if (device == null || recordingMode != io.github.mvolkert.entryrecorder.data.model.RecordingMode.PYTHON_SERVER) {
                repository.deleteActiveServerRecording(row.deviceId)
                continue
            }
            activeServerRecordings[device.id] = ActiveServerRecording(
                eventType = row.eventType,
                maxDurationSeconds = row.maxDurationSeconds,
                startedByThisRequest = row.startedByThisRequest,
                recordingId = row.recordingId,
                startedAtMs = row.startedAtMs
            )
            onActiveChanged()
            Log.i(tag, "Resumed tracking a server recording on ${device.name} across restart (id ${row.recordingId ?: "none"})")
            scope.launch { watchServerRecording(device) }
        }
    }

    /**
     * Registers [Device] on the server and persists the returned id as its `serverDeviceId`, so later starts
     * address it without re-registering. Returns null when registration fails (server unreachable, rejected),
     * which the caller treats as "fall back to local recording". This is the just-in-time safety net behind
     * the best-effort sync in SettingsViewModel.saveDevice.
     */
    private suspend fun ensureRegistered(device: Device): Long? {
        val settings = repository.getSettings()
        val id = serverClient
            .registerDevice(settings.serverBaseUrl, settings.serverApiKey.ifBlank { null }, device)
            .getOrNull() ?: return null
        repository.saveDevice(device.copy(serverDeviceId = id))
        Log.i(tag, "Registered ${device.name} on the server as id $id")
        return id
    }

    /**
     * Keeps the app's view of a server recording in step with the server's own job list.
     *
     * The old behaviour was a `maxDurationSeconds + 2` sleep and an unconditional stop, which drifted in both
     * directions: the phone kept showing REC after the server finalized, and it sent a stop for a job that
     * belongs to an earlier request (`already_recording`) whose duration was never this one's.
     */
    private suspend fun watchServerRecording(device: Device) {
        val tracked = activeServerRecordings[device.id] ?: return
        // All server-facing calls address the device by its server-assigned id; the local maps keep keying by
        // device.id. A device with no server id cannot be probed, so drop the watch rather than spin failures.
        val serverDeviceId = device.serverDeviceId ?: run {
            clearServerRecording(device.id, "has no server id")
            return
        }
        // Anchor the deadline to the recorded start rather than to when this coroutine begins, so a watcher
        // resumed after a process restart honours the time already elapsed instead of granting a fresh
        // maxDurationSeconds. For a fresh start (startedAtMs == now) this equals the previous value.
        val stopAtMs = tracked.startedAtMs + (tracked.maxDurationSeconds * 1000L) + SERVER_STOP_GRACE_MS
        var failedProbes = 0

        while (currentCoroutineContext().isActive) {
            delay(SERVER_RECONCILE_INTERVAL_MS.milliseconds)
            // Cleared by a stop through the app or by a new trigger: this coroutine no longer owns anything.
            val active = activeServerRecordings[device.id] ?: return
            val settings = repository.getSettings()
            val apiKey = settings.serverApiKey.ifBlank { null }
            val probe = serverClient.activeRecordingFor(settings.serverBaseUrl, apiKey, serverDeviceId)
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
                            requestServerStop(serverDeviceId, device.name, settings.serverBaseUrl, apiKey)
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
                        requestServerStop(serverDeviceId, device.name, settings.serverBaseUrl, apiKey)
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
    private suspend fun clearServerRecording(deviceId: Long, reason: String) {
        if (activeServerRecordings.remove(deviceId) != null) {
            onActiveChanged()
            repository.deleteActiveServerRecording(deviceId)
            Log.i(tag, "No longer tracking the server recording of device $deviceId ($reason)")
        }
    }

    private suspend fun requestServerStop(serverDeviceId: Long, label: String, serverUrl: String, apiKey: String?) {
        val outcome = serverClient.stopRecording(serverUrl, apiKey, serverDeviceId)
        val answer = outcome.getOrNull()
        when {
            answer == null -> Log.w(tag, "Server stop for $label failed: ${outcome.exceptionOrNull()?.message}")
            answer.hadActiveJob -> Log.i(tag, "Server stopped the recording of $label")
            // The job had already ended on its own: exactly the drift the reconcile loop exists to catch.
            else -> Log.i(tag, "Server had no job left to stop for $label (it had already ended)")
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
