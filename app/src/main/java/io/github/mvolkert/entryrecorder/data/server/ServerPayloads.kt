package io.github.mvolkert.entryrecorder.data.server

import com.google.gson.annotations.SerializedName

internal data class StartServerRecordingPayload(
    @SerializedName("device_id") val deviceId: Long,
    @SerializedName("device_name") val deviceName: String,
    @SerializedName("rtsp_url") val rtspUrl: String?,
    @SerializedName("snapshot_url") val snapshotUrl: String?,
    @SerializedName("username") val username: String?,
    @SerializedName("password") val password: String?,
    @SerializedName("event_type") val eventType: String,
    @SerializedName("duration_seconds") val durationSeconds: Int,
    @SerializedName("source_mode") val sourceMode: String = "auto",
    @SerializedName("note") val note: String? = null
)

/**
 * Body of `POST/PUT /api/devices` (server `DeviceCreate`/`DeviceUpdate`). Carries the capture config the
 * server stores so it can resolve credentials at record time and serve live view, instead of the app
 * re-sending them on every start.
 */
internal data class ServerDevicePayload(
    @SerializedName("name") val name: String,
    @SerializedName("rtsp_url") val rtspUrl: String?,
    @SerializedName("snapshot_url") val snapshotUrl: String?,
    @SerializedName("username") val username: String?,
    @SerializedName("password") val password: String?,
    @SerializedName("live_mode") val liveMode: String
)

/**
 * Body of `POST /api/alerts/relay` (server `MotionAlertRequest`). Relays one detected-motion event to the
 * [recipient] the monitoring phone configured; the server fans it out to that phone (change
 * `add-motion-notification-to-other-phone`).
 */
internal data class MotionAlertPayload(
    @SerializedName("recipient") val recipient: String,
    @SerializedName("device_name") val deviceName: String,
    @SerializedName("timestamp_ms") val timestampMs: Long,
)
