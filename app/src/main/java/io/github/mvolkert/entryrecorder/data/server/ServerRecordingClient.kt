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
    @SerializedName("total_storage_bytes") val storageBytes: Long
)

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

    suspend fun testConnection(serverUrl: String, apiKey: String? = null): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val base = normalizeUrl(serverUrl)
            val requestBuilder = Request.Builder()
                .url("$base/api/status")
                .get()

            if (!apiKey.isNullOrBlank()) {
                requestBuilder.addHeader("X-API-Key", apiKey)
            }

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

    suspend fun startRecording(
        serverUrl: String,
        apiKey: String?,
        device: DeviceEntity,
        eventType: EventType,
        durationSeconds: Int
    ): Result<Boolean> = withContext(Dispatchers.IO) {
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
            val requestBuilder = Request.Builder()
                .url("$base/api/recordings/start")
                .post(body)

            if (!apiKey.isNullOrBlank()) {
                requestBuilder.addHeader("X-API-Key", apiKey)
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    Log.i(tag, "Started server recording for ${device.name}")
                    Result.success(true)
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

    suspend fun stopRecording(
        serverUrl: String,
        apiKey: String?,
        deviceId: Long
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val base = normalizeUrl(serverUrl)
            val json = "{\"device_id\":$deviceId}"
            val body = json.toRequestBody("application/json".toMediaType())
            val requestBuilder = Request.Builder()
                .url("$base/api/recordings/stop")
                .post(body)

            if (!apiKey.isNullOrBlank()) {
                requestBuilder.addHeader("X-API-Key", apiKey)
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                Result.success(response.isSuccessful)
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
            val requestBuilder = Request.Builder().url(url).get()
            if (!apiKey.isNullOrBlank()) {
                requestBuilder.addHeader("X-API-Key", apiKey)
            }
            client.newCall(requestBuilder.build()).execute().use { response ->
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
