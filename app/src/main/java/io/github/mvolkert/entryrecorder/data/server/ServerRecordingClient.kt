package io.github.mvolkert.entryrecorder.data.server

import timber.log.Timber
import com.google.gson.Gson
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.domain.model.Device
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.data.model.StreamProtocol
import io.github.mvolkert.entryrecorder.di.ControlClient
import io.github.mvolkert.entryrecorder.di.IoDispatcher
import io.github.mvolkert.entryrecorder.di.StreamClient
import io.github.mvolkert.entryrecorder.data.network.withCancellableResponse
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

@Singleton
class ServerRecordingClient @Inject constructor(
    @ControlClient private val client: OkHttpClient,
    /**
     * Without the control-plane read timeout: a video download can legitimately stream for longer than
     * any probe, so it must not be cut off by the 10 s budget the status calls use.
     */
    @StreamClient private val downloadClient: OkHttpClient,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val gson: Gson,
    /** Shares the server's loss/recovery clock with the live-view relay so one outage prints one line. */
    private val serverLink: PythonServerLink,
) : ServerClient {
    private val tag = "ServerRecordingClient"

    private fun normalizeUrl(url: String): String {
        return url.trimEnd('/')
    }

    private val emptyBody = ByteArray(0).toRequestBody(null)

    /** Adds the API key header when one is configured; every endpoint requires it since auth became mandatory. */
    private fun Request.Builder.withApiKey(apiKey: String?): Request.Builder =
        if (!apiKey.isNullOrBlank()) addHeader("X-API-Key", apiKey) else this

    override suspend fun testConnection(serverUrl: String, apiKey: String?): Result<Boolean> = withContext(ioDispatcher) {
        try {
            val base = normalizeUrl(serverUrl)
            val requestBuilder = Request.Builder()
                .url("$base/api/status")
                .get()
                .withApiKey(apiKey)

            client.withCancellableResponse(requestBuilder.build()) { response ->
                if (response.isSuccessful) {
                    Result.success(true)
                } else {
                    Result.failure(Exception("Server returned HTTP ${response.code}"))
                }
            }
        } catch (e: CancellationException) {
            // A cancelled probe is not a failed probe: swallowing it here would make the settings screen
            // report "connection failed" for a test the user simply walked away from.
            throw e
        } catch (e: Exception) {
            Timber.tag(tag).e(e, "Failed to connect to Python server at $serverUrl")
            Result.failure(e)
        }
    }

    /**
     * Asks the server to record and reports what it answered, because "HTTP 200" is not the whole story:
     * an `already_recording` answer means the live job belongs to an earlier request.
     *
     * The device is addressed by its server-assigned [serverDeviceId] and the capture credentials are
     * deliberately omitted: the server resolves the RTSP/snapshot URL and auth from the row the app
     * registered via [registerDevice], so the device password no longer rides on every start call.
     */
    override suspend fun startRecording(
        serverUrl: String,
        apiKey: String?,
        serverDeviceId: Long,
        deviceName: String,
        eventType: EventType,
        durationSeconds: Int
    ): Result<ServerStartResponse> = withContext(ioDispatcher) {
        try {
            val base = normalizeUrl(serverUrl)
            val payload = StartServerRecordingPayload(
                deviceId = serverDeviceId,
                deviceName = deviceName,
                rtspUrl = null,
                snapshotUrl = null,
                username = null,
                password = null,
                eventType = eventType.name,
                durationSeconds = durationSeconds
            )

            val body = gson.toJson(payload).toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$base/api/recordings/start")
                .post(body)
                .withApiKey(apiKey)
                .build()

            client.withCancellableResponse(request) { response ->
                serverLink.reportReachable(serverUrl)
                if (response.isSuccessful) {
                    val answer = runCatching { gson.fromJson(response.body.string(), ServerStartResponse::class.java) }
                        .getOrNull()
                    if (answer == null) {
                        // Success with an unreadable body: assume the job is ours, which is what the caller
                        // did before the answer was read at all, so an older server keeps working.
                        Timber.tag(tag).w("Start answer for $deviceName carried no readable body; assuming it started")
                        Result.success(ServerStartResponse(status = "started", deviceId = serverDeviceId))
                    } else {
                        Timber.tag(tag).i(
                            "Server recording ${answer.status} for $deviceName" +
                                (answer.recordingId?.let { " (server recording id $it)" } ?: "")
                        )
                        Result.success(answer)
                    }
                } else {
                    val err = "HTTP ${response.code}: ${response.body.string()}"
                    Timber.tag(tag).w("Server recording error: $err")
                    Result.failure(Exception(err))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            serverLink.reportFailure(serverUrl, e)
            Timber.tag(tag).e(e, "Server recording request failed for $deviceName")
            Result.failure(e)
        }
    }

    /**
     * Builds the `POST/PUT /api/devices` body from a local [Device]. `live_mode` is `snapshot` only for a
     * device pinned to HTTP-snapshot polling; every other protocol has a usable RTSP URL (the entity
     * always derives one), so the server can pull RTSP for live view.
     */
    private fun serverDevicePayload(device: Device): ServerDevicePayload = ServerDevicePayload(
        name = device.name,
        rtspUrl = device.rtspStreamUrl,
        snapshotUrl = device.snapshotUrl,
        username = device.username,
        password = device.password,
        liveMode = if (device.streamProtocol == StreamProtocol.HTTP_SNAPSHOT) "snapshot" else "rtsp"
    )

    /**
     * Registers [Device] on the server (`POST /api/devices`) and returns the server-assigned row id, which
     * the caller persists as `device.serverDeviceId`. The server rejects a device with neither an
     * RTSP nor a snapshot URL, so a fully-blank camera surfaces here as a failure the caller can fall back on.
     */
    override suspend fun registerDevice(
        serverUrl: String,
        apiKey: String?,
        device: Device
    ): Result<Long> = withContext(ioDispatcher) {
        try {
            val base = normalizeUrl(serverUrl)
            val body = gson.toJson(serverDevicePayload(device)).toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$base/api/devices")
                .post(body)
                .withApiKey(apiKey)
                .build()
            client.withCancellableResponse(request) { response ->
                serverLink.reportReachable(serverUrl)
                val answer = runCatching { gson.fromJson(response.body.string(), ServerDeviceDto::class.java) }.getOrNull()
                if (response.isSuccessful && answer != null && answer.id > 0) {
                    Timber.tag(tag).i("Registered device ${device.name} on server as id ${answer.id}")
                    Result.success(answer.id)
                } else {
                    Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            serverLink.reportFailure(serverUrl, e)
            Timber.tag(tag).e(e, "Registering device ${device.name} on server failed")
            Result.failure(e)
        }
    }

    /** Pushes the current [Device] config onto an already-registered server row (`PUT /api/devices/{id}`). */
    override suspend fun updateServerDevice(
        serverUrl: String,
        apiKey: String?,
        serverDeviceId: Long,
        device: Device
    ): Result<Unit> = withContext(ioDispatcher) {
        try {
            val base = normalizeUrl(serverUrl)
            val body = gson.toJson(serverDevicePayload(device)).toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$base/api/devices/$serverDeviceId")
                .put(body)
                .withApiKey(apiKey)
                .build()
            client.withCancellableResponse(request) { response ->
                serverLink.reportReachable(serverUrl)
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("HTTP ${response.code}: ${response.body.string()}"))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            serverLink.reportFailure(serverUrl, e)
            Timber.tag(tag).e(e, "Updating server device $serverDeviceId failed")
            Result.failure(e)
        }
    }

    /**
     * Best-effort removal of a previously-registered server row (`DELETE /api/devices/{id}`). The server
     * intentionally keeps the device's recordings (no cascade), and a failure here must not block deleting
     * the device locally — the caller treats this as fire-and-forget.
     */
    override suspend fun deleteServerDevice(
        serverUrl: String,
        apiKey: String?,
        serverDeviceId: Long
    ): Result<Unit> = withContext(ioDispatcher) {
        try {
            val base = normalizeUrl(serverUrl)
            val request = Request.Builder()
                .url("$base/api/devices/$serverDeviceId")
                .delete(emptyBody)
                .withApiKey(apiKey)
                .build()
            client.withCancellableResponse(request) { response ->
                // 404 is fine: the row was already gone, which is the state we wanted.
                if (response.isSuccessful || response.code == 404) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("HTTP ${response.code}: ${response.body.string()}"))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(tag).e(e, "Deleting server device $serverDeviceId failed")
            Result.failure(e)
        }
    }

    /** Whether the server still runs a job for [deviceId]; null success means "the server has none". */
    override suspend fun activeRecordingFor(
        serverUrl: String,
        apiKey: String?,
        deviceId: Long
    ): Result<ServerActiveRecording?> = withContext(ioDispatcher) {
        try {
            val base = normalizeUrl(serverUrl)
            val request = Request.Builder()
                .url("$base/api/status")
                .get()
                .withApiKey(apiKey)
                .build()

            client.withCancellableResponse(request) { response ->
                serverLink.reportReachable(serverUrl)
                if (!response.isSuccessful) {
                    Result.failure(Exception("HTTP ${response.code}"))
                } else {
                    val status = gson.fromJson(response.body.string(), ServerStatusDto::class.java)
                    Result.success(status.activeRecordings.orEmpty().firstOrNull { it.deviceId == deviceId })
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            serverLink.reportFailure(serverUrl, e)
            Timber.tag(tag).e(e, "Server status probe failed for device $deviceId")
            Result.failure(e)
        }
    }

    override suspend fun stopRecording(
        serverUrl: String,
        apiKey: String?,
        deviceId: Long
    ): Result<ServerStopResponse> = withContext(ioDispatcher) {
        try {
            val base = normalizeUrl(serverUrl)
            val json = "{\"device_id\":$deviceId}"
            val body = json.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$base/api/recordings/stop")
                .post(body)
                .withApiKey(apiKey)
                .build()

            client.withCancellableResponse(request) { response ->
                serverLink.reportReachable(serverUrl)
                if (!response.isSuccessful) {
                    Result.failure(Exception("HTTP ${response.code}: ${response.body.string()}"))
                } else {
                    val answer = runCatching { gson.fromJson(response.body.string(), ServerStopResponse::class.java) }
                        .getOrNull()
                        ?: ServerStopResponse(status = null, deviceId = deviceId)
                    Result.success(answer)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            serverLink.reportFailure(serverUrl, e)
            Timber.tag(tag).e(e, "Server stop recording failed for device $deviceId")
            Result.failure(e)
        }
    }

    /**
     * Fetch the recordings stored on the server (data-layer foundation for surfacing server
     * recordings in the app gallery). Returns server-relative URLs that callers prefix with the base URL.
     */
    override suspend fun listRecordings(
        serverUrl: String,
        apiKey: String?,
        deviceId: Long?,
        limit: Int
    ): Result<List<ServerRecordingDto>> = withContext(ioDispatcher) {
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
            client.withCancellableResponse(request.build()) { response ->
                if (response.isSuccessful) {
                    val body = response.body.string()
                    val type = object : com.google.gson.reflect.TypeToken<List<ServerRecordingDto>>() {}.type
                    val list: List<ServerRecordingDto> = gson.fromJson(body, type)
                    Result.success(list)
                } else {
                    Result.failure(Exception("HTTP ${response.code}: ${response.body.string()}"))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(tag).e(e, "Listing server recordings failed")
            Result.failure(e)
        }
    }

    /**
     * Sets or clears the protection flag the server's retention/quota cleanup respects. The server takes
     * the flag as a query parameter, so the request carries an empty body.
     */
    override suspend fun setProtected(
        serverUrl: String,
        apiKey: String?,
        recordingId: Long,
        isProtected: Boolean
    ): Result<Unit> = withContext(ioDispatcher) {
        try {
            val base = normalizeUrl(serverUrl)
            val request = Request.Builder()
                .url("$base/api/recordings/$recordingId/protect?is_protected=$isProtected")
                .post(emptyBody)
                .withApiKey(apiKey)
                .build()
            client.withCancellableResponse(request) { response ->
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("HTTP ${response.code}: ${response.body.string()}"))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(tag).e(e, "Protecting server recording $recordingId failed")
            Result.failure(e)
        }
    }

    /** Removes a completed server recording and its files. A protected row is still deleted here: only
     * automatic cleanup honours the flag, not an explicit user delete. */
    override suspend fun deleteRecording(
        serverUrl: String,
        apiKey: String?,
        recordingId: Long
    ): Result<Unit> = withContext(ioDispatcher) {
        try {
            val base = normalizeUrl(serverUrl)
            val request = Request.Builder()
                .url("$base/api/recordings/$recordingId")
                .delete(emptyBody)
                .withApiKey(apiKey)
                .build()
            client.withCancellableResponse(request) { response ->
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("HTTP ${response.code}: ${response.body.string()}"))
                }
            }
        } catch (e: Exception) {
            Timber.tag(tag).e(e, "Deleting server recording $recordingId failed")
            Result.failure(e)
        }
    }

    /**
     * Streams the video at [absoluteUrl] (already carrying the `?api_key=` param, since OkHttp media
     * fetches share the Coil/ExoPlayer constraint of not setting the header) into [targetDir]. The file
     * extension comes from the response Content-Type so a `.mkv` export is not mislabelled as `.mp4`.
     * [onProgress] reports 0..100 when the server sends a Content-Length, and is not called otherwise.
     *
     * Cancellable end to end: [withCancellableResponse] aborts the underlying call as soon as the caller's
     * job is cancelled, and the chunk loop checks for cancellation before every read, so a cancelled
     * export stops pushing bytes instead of finishing the file first.
     */
    override suspend fun downloadVideo(
        absoluteUrl: String,
        targetDir: File,
        baseName: String,
        onProgress: (percent: Int) -> Unit
    ): Result<File> = withContext(ioDispatcher) {
        try {
            val request = Request.Builder().url(absoluteUrl).get().build()
            downloadClient.withCancellableResponse(request) { response ->
                val body = response.body
                if (!response.isSuccessful) {
                    Result.failure(Exception("HTTP ${response.code}"))
                } else {
                    val contentType = response.header("Content-Type").orEmpty()
                    val extension = when {
                        contentType.contains("matroska", ignoreCase = true) -> "mkv"
                        else -> "mp4"
                    }
                    targetDir.mkdirs()
                    val dest = File(targetDir, "$baseName.$extension")
                    val total = body.contentLength().takeIf { it > 0 } ?: 0L
                    var written = 0L
                    body.byteStream().use { input ->
                        dest.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                // Checked per chunk: a cancelled run must not keep writing a file that
                                // nobody asked for anymore, and must not report progress while doing so.
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                written += read
                                if (total > 0) onProgress((written * 100 / total).toInt().coerceIn(0, 100))
                            }
                        }
                    }
                    Result.success(dest)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(tag).e(e, "Downloading server video failed")
            Result.failure(e)
        }
    }

    /**
     * `POST /api/alerts/relay` (change `add-motion-notification-to-other-phone`): hand the server a motion
     * alert for [deviceName] addressed to [recipient]. Fire-and-report: a failure is returned, never thrown
     * (cancellation aside), so the monitor loop keeps running when the relay endpoint is unreachable.
     */
    override suspend fun relayMotionAlert(
        serverUrl: String,
        apiKey: String?,
        recipient: String,
        deviceName: String,
        timestampMs: Long
    ): Result<Unit> = withContext(ioDispatcher) {
        try {
            val base = normalizeUrl(serverUrl)
            val payload = MotionAlertPayload(
                recipient = recipient,
                deviceName = deviceName,
                timestampMs = timestampMs,
            )
            val body = gson.toJson(payload).toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$base/api/alerts/relay")
                .post(body)
                .withApiKey(apiKey)
                .build()
            client.withCancellableResponse(request) { response ->
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("HTTP ${response.code}: ${response.body.string()}"))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(tag).e(e, "Relaying motion alert to $recipient for $deviceName failed")
            Result.failure(e)
        }
    }
}
