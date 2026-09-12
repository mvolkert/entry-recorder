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
    @SerializedName("duration_seconds") val durationSeconds: Int
)

data class ServerStatusDto(
    val status: String,
    val version: String,
    @SerializedName("active_recordings_count") val activeCount: Int,
    @SerializedName("total_recordings_count") val totalCount: Int,
    @SerializedName("total_storage_bytes") val storageBytes: Long
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
                    val err = "HTTP ${response.code}: ${response.body?.string()}"
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
}
