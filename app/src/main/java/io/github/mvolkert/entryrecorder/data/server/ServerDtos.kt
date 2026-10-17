package io.github.mvolkert.entryrecorder.data.server

import com.google.gson.annotations.SerializedName

/**
 * Answer of the `/api/devices` endpoints (server `DeviceResponse`). Only [id] is read back — the app's
 * source of truth for a device stays the local Room row; this exists to capture the server-assigned id.
 */
internal data class ServerDeviceDto(
    val id: Long
)

data class ServerStatusDto(
    val status: String,
    val version: String,
    @SerializedName("active_recordings_count") val activeCount: Int,
    @SerializedName("total_recordings_count") val totalCount: Int,
    @SerializedName("total_storage_bytes") val storageBytes: Long,
    // Nullable on purpose: Gson writes the field default (here an absent property) as null, not as the
    // Kotlin default, so a server that does not report its jobs must not crash the reader.
    @SerializedName("active_recordings") val activeRecordings: List<ServerActiveRecording>? = null
)

/**
 * One entry of `/api/status.active_recordings`: a job the server is running right now. This is what the
 * app reconciles its own "server is recording" view against, since the server hands back no row id at
 * start (see [ServerStartResponse.recordingId]).
 */
data class ServerActiveRecording(
    @SerializedName("device_id") val deviceId: Long,
    @SerializedName("device_name") val deviceName: String,
    @SerializedName("event_type") val eventType: String,
    @SerializedName("elapsed_seconds") val elapsedSeconds: Int,
    @SerializedName("max_duration_seconds") val maxDurationSeconds: Int
)

/**
 * Answer of `POST /api/recordings/start`. `status` is `started` or `already_recording`; the latter means a
 * job from an earlier request is still running, which this client must not stop on its own schedule.
 *
 * `recordingId` is null with today's server: the row is only inserted when the job is finalized, so
 * reconciling through `GET /api/recordings/{id}` needs the server to return the id from start (Phase S).
 */
data class ServerStartResponse(
    val status: String?,
    @SerializedName("device_id") val deviceId: Long,
    @SerializedName("recording_id") val recordingId: Long? = null
) {
    /** False only when the server explicitly said a job was already running for this device. */
    val startedByThisRequest: Boolean get() = !status.equals("already_recording", ignoreCase = true)
}

/** Answer of `POST /api/recordings/stop`; `not_recording` means the job had already ended server-side. */
data class ServerStopResponse(
    val status: String?,
    @SerializedName("device_id") val deviceId: Long
) {
    val hadActiveJob: Boolean get() = status.equals("stopped", ignoreCase = true)
}

/**
 * A recording stored on the Python server. `videoUrl`/`thumbnailUrl` are server-relative paths
 * (e.g. `/api/recordings/5/video`) that must be prefixed with the configured server base URL.
 */
data class ServerRecordingDto(
    val id: Long,
    @SerializedName("device_id") val deviceId: Long,
    @SerializedName("device_name") val deviceName: String,
    @SerializedName("event_type") val eventType: String,
    val timestamp: Long,
    @SerializedName("duration_seconds") val durationSeconds: Int,
    @SerializedName("file_size_bytes") val fileSizeBytes: Long,
    @SerializedName("is_protected") val isProtected: Boolean,
    val note: String?,
    @SerializedName("video_url") val videoUrl: String?,
    @SerializedName("thumbnail_url") val thumbnailUrl: String?
)
