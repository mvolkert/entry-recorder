package io.github.mvolkert.entryrecorder.domain.server

import io.github.mvolkert.entryrecorder.data.model.RecordingMode
import io.github.mvolkert.entryrecorder.data.repository.IntercomRepository
import io.github.mvolkert.entryrecorder.data.server.ServerClient
import io.github.mvolkert.entryrecorder.domain.model.Device
import javax.inject.Inject

/** What the best-effort server sync of a *save* ended up doing; the local write always happened regardless. */
sealed interface ServerSyncResult {
    /** Not in PYTHON_SERVER mode, so there is nothing to sync. */
    data object Skipped : ServerSyncResult
    /** First save registered the device; [serverDeviceId] is now persisted on the local row. */
    data class Registered(val serverDeviceId: Long) : ServerSyncResult
    /** The device was already registered; its server row was updated in place. */
    data class Updated(val serverDeviceId: Long) : ServerSyncResult
    /** Registration failed; the record-time path in the recorder registers just in time, so the save is deferred. */
    data class RegistrationDeferred(val cause: Throwable) : ServerSyncResult
    /** An already-registered device's update call failed. */
    data class UpdateFailed(val serverDeviceId: Long, val cause: Throwable) : ServerSyncResult
}

/** The outcome of a save: the authoritative local row id plus what the best-effort server sync did. */
data class DeviceSaveOutcome(val savedId: Long, val sync: ServerSyncResult)

/** What the best-effort server delete of a *delete* did; the local row always went regardless. */
sealed interface ServerDeleteResult {
    /** No server id was assigned, so there was nothing to drop on the server. */
    data object NotRegistered : ServerDeleteResult
    data object Deleted : ServerDeleteResult
    data class Failed(val serverDeviceId: Long, val cause: Throwable) : ServerDeleteResult
}

/**
 * The server device-sync policy behind Settings save/delete: persist locally (authoritative), then — only in
 * PYTHON_SERVER mode — best-effort mirror the change to the Python server (register the first time, update
 * thereafter, delete the row when one exists). The local write is never blocked by the server: a failed
 * registration leaves `serverDeviceId` null and the record-time path registers just in time.
 *
 * Extracted so the ViewModel keeps only state + intent plumbing and this flow becomes unit-testable without a
 * ViewModel scope. It reports what it did rather than logging it, so the caller decides how to surface a
 * deferred or failed sync.
 */
class SyncDeviceUseCase @Inject constructor(
    private val repository: IntercomRepository,
    private val serverClient: ServerClient,
) {
    /** Saves [device] locally, then best-effort syncs it to the server. Returns the local id + sync outcome. */
    suspend fun saveAndSync(device: Device): DeviceSaveOutcome {
        val savedId = repository.saveDevice(device)
        val settings = repository.getSettings()
        if (settings.recordingMode != RecordingMode.PYTHON_SERVER) {
            return DeviceSaveOutcome(savedId, ServerSyncResult.Skipped)
        }
        val current = repository.getDeviceById(savedId) ?: return DeviceSaveOutcome(savedId, ServerSyncResult.Skipped)
        val serverUrl = settings.serverBaseUrl
        val apiKey = settings.serverApiKey.ifBlank { null }
        val serverDeviceId = current.serverDeviceId
        return if (serverDeviceId == null) {
            val result = serverClient.registerDevice(serverUrl, apiKey, current)
            result.fold(
                onSuccess = { id ->
                    repository.saveDevice(current.copy(serverDeviceId = id))
                    DeviceSaveOutcome(savedId, ServerSyncResult.Registered(id))
                },
                onFailure = { DeviceSaveOutcome(savedId, ServerSyncResult.RegistrationDeferred(it)) },
            )
        } else {
            val result = serverClient.updateServerDevice(serverUrl, apiKey, serverDeviceId, current)
            result.fold(
                onSuccess = { DeviceSaveOutcome(savedId, ServerSyncResult.Updated(serverDeviceId)) },
                onFailure = { DeviceSaveOutcome(savedId, ServerSyncResult.UpdateFailed(serverDeviceId, it)) },
            )
        }
    }

    /** Drops the server row when one exists (best-effort, the server keeps its recordings), then deletes locally. */
    suspend fun deleteAndSync(device: Device): ServerDeleteResult {
        val serverDeviceId = device.serverDeviceId
        if (serverDeviceId != null) {
            val settings = repository.getSettings()
            val result = serverClient.deleteServerDevice(
                settings.serverBaseUrl,
                settings.serverApiKey.ifBlank { null },
                serverDeviceId,
            )
            // Local delete happens regardless of the server answer, so the outcome is reported only for logging.
            val syncResult = result.fold({ ServerDeleteResult.Deleted }, { ServerDeleteResult.Failed(serverDeviceId, it) })
            repository.deleteDevice(device)
            return syncResult
        }
        repository.deleteDevice(device)
        return ServerDeleteResult.NotRegistered
    }
}
