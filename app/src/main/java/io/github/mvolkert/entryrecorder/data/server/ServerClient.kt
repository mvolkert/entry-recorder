package io.github.mvolkert.entryrecorder.data.server

import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.domain.model.Device
import io.github.mvolkert.entryrecorder.data.model.EventType
import java.io.File

/**
 * The Python recording server's REST surface as a JVM-only contract. [ServerRecordingClient] is the
 * single implementation; the ViewModels code against this interface so their server-dependent branches
 * — registration, protect, delete, download — can be driven from an ordinary unit test.
 *
 * Every method keeps the [Result] convention of the implementation: a failure is the HTTP or transport
 * error, never a thrown exception (cancellation notwithstanding). Default arguments live here, not on
 * the implementation.
 */
interface ServerClient {

    /** Probe `/api/status`; a failure carries why the server is unreachable or refusing the key. */
    suspend fun testConnection(serverUrl: String, apiKey: String? = null): Result<Boolean>

    /** Asks the server to record and reports what it answered ("started" vs "already_recording"). */
    suspend fun startRecording(
        serverUrl: String,
        apiKey: String?,
        serverDeviceId: Long,
        deviceName: String,
        eventType: EventType,
        durationSeconds: Int
    ): Result<ServerStartResponse>

    /** Registers [Device] on the server and returns the server-assigned row id. */
    suspend fun registerDevice(
        serverUrl: String,
        apiKey: String?,
        device: Device
    ): Result<Long>

    /** Pushes the current [Device] config onto an already-registered server row. */
    suspend fun updateServerDevice(
        serverUrl: String,
        apiKey: String?,
        serverDeviceId: Long,
        device: Device
    ): Result<Unit>

    /** Best-effort removal of a previously-registered server row; a missing row counts as success. */
    suspend fun deleteServerDevice(
        serverUrl: String,
        apiKey: String?,
        serverDeviceId: Long
    ): Result<Unit>

    /** Whether the server still runs a job for [deviceId]; a null success means "the server has none". */
    suspend fun activeRecordingFor(
        serverUrl: String,
        apiKey: String?,
        deviceId: Long
    ): Result<ServerActiveRecording?>

    suspend fun stopRecording(
        serverUrl: String,
        apiKey: String?,
        deviceId: Long
    ): Result<ServerStopResponse>

    /** The recordings stored on the server, newest first, with server-relative URLs. */
    suspend fun listRecordings(
        serverUrl: String,
        apiKey: String?,
        deviceId: Long? = null,
        limit: Int = 100
    ): Result<List<ServerRecordingDto>>

    /** Sets or clears the protection flag the server's retention cleanup respects. */
    suspend fun setProtected(
        serverUrl: String,
        apiKey: String?,
        recordingId: Long,
        isProtected: Boolean
    ): Result<Unit>

    /** Removes a completed server recording and its files. */
    suspend fun deleteRecording(
        serverUrl: String,
        apiKey: String?,
        recordingId: Long
    ): Result<Unit>

    /** Streams the video at [absoluteUrl] into [targetDir]; [onProgress] reports 0..100 when the size is known. */
    suspend fun downloadVideo(
        absoluteUrl: String,
        targetDir: File,
        baseName: String,
        onProgress: (percent: Int) -> Unit = {}
    ): Result<File>

    /**
     * Relays a detected-motion alert for [deviceName] to [recipient] through the server
     * (`POST /api/alerts/relay`, change `add-motion-notification-to-other-phone`). [recipient] is the target
     * phone's UnifiedPush address, which the server POSTs the alert body to — the phone keeps the server as its
     * only outbound destination.
     *
     * A failed [Result] distinguishes two things by its exception type: an [java.io.IOException] is the server
     * not being reachable, while an HTTP status in the message (400 for an address the server will not dial,
     * 502 for a push distributor that rejected the alert) means the server answered and the alert was not
     * delivered. Only the first says anything about the link.
     */
    suspend fun relayMotionAlert(
        serverUrl: String,
        apiKey: String?,
        recipient: String,
        deviceName: String,
        timestampMs: Long
    ): Result<Unit>
}
