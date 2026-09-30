package io.github.mvolkert.entryrecorder.data.server

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.data.model.EventType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class StartServerRecordingPayload(
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

class ServerRecordingClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson()
) {
    private val tag = "ServerRecordingClient"

    private fun normalizeUrl(url: String): String {
        return url.trimEnd('/')
    }

    /** Adds the API key header when one is configured; every endpoint requires it since auth became mandatory. */
    private fun Request.Builder.withApiKey(apiKey: String?): Request.Builder =
        if (!apiKey.isNullOrBlank()) addHeader("X-API-Key", apiKey) else this

    suspend fun testConnection(serverUrl: String, apiKey: String? = null): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val base = normalizeUrl(serverUrl)
            val requestBuilder = Request.Builder()
                .url("$base/api/status")
                .get()
                .withApiKey(apiKey)

            client.newCall(requestBuilder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(true)
                } else {
                    Result.failure(Exception("Server returned HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to connect to Python server at $serverUrl", e)
            Result.failure(e)
        }
    }

    /**
     * Asks the server to record and reports what it answered, because "HTTP 200" is not the whole story:
     * an `already_recording` answer means the live job belongs to an earlier request.
     */
    suspend fun startRecording(
        serverUrl: String,
        apiKey: String?,
        device: DeviceEntity,
        eventType: EventType,
        durationSeconds: Int
    ): Result<ServerStartResponse> = withContext(Dispatchers.IO) {
        try {
            val base = normalizeUrl(serverUrl)
            val payload = StartServerRecordingPayload(
                deviceId = device.id,
                deviceName = device.name,
                rtspUrl = device.rtspStreamUrl,
                snapshotUrl = device.snapshotUrl,
                username = device.username,
                password = device.password,
                eventType = eventType.name,
                durationSeconds = durationSeconds
            )

            val body = gson.toJson(payload).toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$base/api/recordings/start")
                .post(body)
                .withApiKey(apiKey)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val answer = runCatching { gson.fromJson(response.body.string(), ServerStartResponse::class.java) }
                        .getOrNull()
                    if (answer == null) {
                        // Success with an unreadable body: assume the job is ours, which is what the caller
                        // did before the answer was read at all, so an older server keeps working.
                        Log.w(tag, "Start answer for ${device.name} carried no readable body; assuming it started")
                        Result.success(ServerStartResponse(status = "started", deviceId = device.id))
                    } else {
                        Log.i(
                            tag,
                            "Server recording ${answer.status} for ${device.name}" +
                                (answer.recordingId?.let { " (server recording id $it)" } ?: "")
                        )
                        Result.success(answer)
                    }
                } else {
                    val err = "HTTP ${response.code}: ${response.body.string()}"
                    Log.w(tag, "Server recording error: $err")
                    Result.failure(Exception(err))
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Server recording request failed for ${device.name}", e)
            Result.failure(e)
        }
    }

    /** Whether the server still runs a job for [deviceId]; null success means "the server has none". */
    suspend fun activeRecordingFor(
        serverUrl: String,
        apiKey: String?,
        deviceId: Long
    ): Result<ServerActiveRecording?> = withContext(Dispatchers.IO) {
        try {
            val base = normalizeUrl(serverUrl)
            val request = Request.Builder()
                .url("$base/api/status")
                .get()
                .withApiKey(apiKey)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Result.failure(Exception("HTTP ${response.code}"))
                } else {
                    val status = gson.fromJson(response.body.string(), ServerStatusDto::class.java)
                    Result.success(status.activeRecordings.orEmpty().firstOrNull { it.deviceId == deviceId })
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Server status probe failed for device $deviceId", e)
            Result.failure(e)
        }
    }

    suspend fun stopRecording(
        serverUrl: String,
        apiKey: String?,
        deviceId: Long
    ): Result<ServerStopResponse> = withContext(Dispatchers.IO) {
        try {
            val base = normalizeUrl(serverUrl)
            val json = "{\"device_id\":$deviceId}"
            val body = json.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$base/api/recordings/stop")
                .post(body)
                .withApiKey(apiKey)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Result.failure(Exception("HTTP ${response.code}: ${response.body.string()}"))
                } else {
                    val answer = runCatching { gson.fromJson(response.body.string(), ServerStopResponse::class.java) }
                        .getOrNull()
                        ?: ServerStopResponse(status = null, deviceId = deviceId)
                    Result.success(answer)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Server stop recording failed for device $deviceId", e)
            Result.failure(e)
        }
    }

    /**
     * Fetch the recordings stored on the server (data-layer foundation for surfacing server
     * recordings in the app gallery). Returns server-relative URLs that callers prefix with the base URL.
     */
    suspend fun listRecordings(
        serverUrl: String,
        apiKey: String?,
        deviceId: Long? = null,
        limit: Int = 100
    ): Result<List<ServerRecordingDto>> = withContext(Dispatchers.IO) {
        try {
            val base = normalizeUrl(serverUrl)
            val url = buildString {
                append("$base/api/recordings?limit=").append(limit)
                if (deviceId != null) append("&device_id=").append(deviceId)
            }
            val request = Request.Builder()
                .url(url)
                .get()
                .withApiKey(apiKey)
            client.newCall(request.build()).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body.string()
                    val type = object : com.google.gson.reflect.TypeToken<List<ServerRecordingDto>>() {}.type
                    val list: List<ServerRecordingDto> = gson.fromJson(body, type)
                    Result.success(list)
                } else {
                    Result.failure(Exception("HTTP ${response.code}: ${response.body.string()}"))
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Listing server recordings failed", e)
            Result.failure(e)
        }
    }
}
