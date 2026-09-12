package io.github.mvolkert.entryrecorder.data.device

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.domain.device.IntercomDevice
import io.github.mvolkert.entryrecorder.domain.device.IntercomEvent
import io.github.mvolkert.entryrecorder.domain.device.IntercomEventListener
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class TwoNIPVersoDevice(
    override val deviceEntity: DeviceEntity
) : IntercomDevice {

    private val tag = "2N_Verso_${deviceEntity.id}"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isMonitoring = AtomicBoolean(false)
    private var eventSource: EventSource? = null
    private var pollingJob: Job? = null

    // OkHttpClient with Digest / Basic authentication support
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // Indefinite for SSE stream
            .writeTimeout(10, TimeUnit.SECONDS)
            .authenticator(TwoNDigestAuthenticator(deviceEntity.username, deviceEntity.password))
            .retryOnConnectionFailure(true)
            .build()
    }

    override suspend fun testConnection(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = "${deviceEntity.httpBaseUrl}/api/system/info"
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(true)
                } else {
                    // Try fallback info endpoint
                    val fallbackUrl = "${deviceEntity.httpBaseUrl}/api/info/status"
                    val fallbackReq = Request.Builder().url(fallbackUrl).get().build()
                    client.newCall(fallbackReq).execute().use { fbResponse ->
                        if (fbResponse.isSuccessful) Result.success(true)
                        else Result.failure(IOException("HTTP ${response.code}: ${response.message}"))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Connection test failed", e)
            Result.failure(e)
        }
    }

    override suspend fun startMonitoring(listener: IntercomEventListener) = withContext(Dispatchers.IO) {
        if (isMonitoring.getAndSet(true)) return@withContext

        Log.i(tag, "Starting 2N IP Verso monitoring on ${deviceEntity.ipAddress} (FW 2.50+)")
        listener.onEvent(IntercomEvent.ConnectionState(deviceEntity, true, "Connecting to 2N IP Verso..."))

        // Try SSE Event Stream first (/api/event/subscribe)
        startSseEventListener(listener)
    }

    private fun startSseEventListener(listener: IntercomEventListener) {
        val sseUrl = "${deviceEntity.httpBaseUrl}/api/event/subscribe?events=MotionDetected,KeyPressed,CallStateChanged,NoiseDetected"
        val request = Request.Builder()
            .url(sseUrl)
            .header("Accept", "text/event-stream")
            .build()

        val factory = EventSources.createFactory(client)
        eventSource = factory.newEventSource(request, object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                Log.d(tag, "SSE Event Stream opened with 2N Verso")
                listener.onEvent(IntercomEvent.ConnectionState(deviceEntity, true, "Connected"))
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                Log.d(tag, "2N Event received: type=$type data=$data")
                handleRaw2NEvent(data, listener)
            }

            override fun onClosed(eventSource: EventSource) {
                Log.w(tag, "SSE Event Stream closed by 2N Verso. Fallback to polling if active.")
                if (isMonitoring.get()) {
                    listener.onEvent(IntercomEvent.ConnectionState(deviceEntity, false, "Stream closed, retrying..."))
                    scheduleReconnect(listener)
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                Log.w(tag, "SSE Failure (${response?.code}): ${t?.message}. Starting polling fallback.")
                if (isMonitoring.get()) {
                    startPollingFallback(listener)
                }
            }
        })
    }

    private fun handleRaw2NEvent(data: String, listener: IntercomEventListener) {
        try {
            val json = JsonParser.parseString(data).asJsonObject
            val eventsArray = if (json.has("events") && json.get("events").isJsonArray) {
                json.getAsJsonArray("events")
            } else if (json.has("event") && json.get("event").isJsonObject) {
                listOf(json.getAsJsonObject("event"))
            } else {
                listOf(json)
            }

            for (element in eventsArray) {
                val eventObj = element.asJsonObject
                val eventType = eventObj.get("event")?.asString ?: eventObj.get("type")?.asString ?: ""
                val params = eventObj.getAsJsonObject("params") ?: JsonObject()

                when (eventType) {
                    "MotionDetected" -> {
                        val state = params.get("state")?.asBoolean ?: (params.get("state")?.asString == "active")
                        if (state) {
                            listener.onEvent(IntercomEvent.MotionStarted(deviceEntity))
                        } else {
                            listener.onEvent(IntercomEvent.MotionEnded(deviceEntity))
                        }
                    }
                    "NoiseDetected" -> {
                        val state = params.get("state")?.asBoolean ?: (params.get("state")?.asString == "active")
                        if (state) {
                            listener.onEvent(IntercomEvent.NoiseStarted(deviceEntity))
                        } else {
                            listener.onEvent(IntercomEvent.NoiseEnded(deviceEntity))
                        }
                    }
                    "KeyPressed" -> {
                        // Key 1 is typical main doorbell ring button on 2N Verso
                        listener.onEvent(IntercomEvent.DoorbellRung(deviceEntity, callerNumber = "Doorbell Button"))
                    }
                    "CallStateChanged" -> {
                        val state = params.get("state")?.asString ?: ""
                        val direction = params.get("direction")?.asString ?: ""
                        Log.d(tag, "2N CallStateChanged: state=$state direction=$direction")
                        if (state.equals("incoming", ignoreCase = true) || state.equals("ringing", ignoreCase = true) || state.equals("dialing", ignoreCase = true)) {
                            listener.onEvent(IntercomEvent.DoorbellRung(deviceEntity, callerNumber = params.get("peer")?.asString))
                        }
                        listener.onEvent(IntercomEvent.CallState(deviceEntity, state))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse 2N event: $data", e)
        }
    }

    private fun startPollingFallback(listener: IntercomEventListener) {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            Log.i(tag, "Starting 2N HTTP polling fallback loop")
            var lastMotionState = false
            var lastNoiseState = false

            while (isActive && isMonitoring.get()) {
                try {
                    // Check Motion Status
                    val motionUrl = "${deviceEntity.httpBaseUrl}/api/motion/status"
                    val req = Request.Builder().url(motionUrl).get().build()
                    client.newCall(req).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string()
                            if (body != null) {
                                val json = JsonParser.parseString(body).asJsonObject
                                val motion = json.getAsJsonObject("result")?.get("active")?.asBoolean ?: false
                                if (motion && !lastMotionState) {
                                    listener.onEvent(IntercomEvent.MotionStarted(deviceEntity))
                                } else if (!motion && lastMotionState) {
                                    listener.onEvent(IntercomEvent.MotionEnded(deviceEntity))
                                }
                                lastMotionState = motion
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Motion polling error: ${e.message}")
                }

                try {
                    // Check Noise Status
                    val noiseUrl = "${deviceEntity.httpBaseUrl}/api/noise/status"
                    val req = Request.Builder().url(noiseUrl).get().build()
                    client.newCall(req).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string()
                            if (body != null) {
                                val json = JsonParser.parseString(body).asJsonObject
                                val noise = json.getAsJsonObject("result")?.get("active")?.asBoolean ?: false
                                if (noise && !lastNoiseState) {
                                    listener.onEvent(IntercomEvent.NoiseStarted(deviceEntity))
                                } else if (!noise && lastNoiseState) {
                                    listener.onEvent(IntercomEvent.NoiseEnded(deviceEntity))
                                }
                                lastNoiseState = noise
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Noise polling error: ${e.message}")
                }

                delay(1500)
            }
        }
    }

    private fun scheduleReconnect(listener: IntercomEventListener) {
        scope.launch {
            delay(5000)
            if (isMonitoring.get()) {
                startSseEventListener(listener)
            }
        }
    }

    override suspend fun stopMonitoring() {
        withContext(Dispatchers.IO) {
            isMonitoring.set(false)
            eventSource?.cancel()
            eventSource = null
            pollingJob?.cancel()
            pollingJob = null
            Log.i(tag, "2N monitoring stopped")
        }
    }
}

/**
 * Handles HTTP Digest and Basic Authentication headers for 2N IP Intercoms
 */
class TwoNDigestAuthenticator(
    private val username: String,
    private val password: String
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.request.header("Authorization") != null) {
            return null // Give up if already authenticated and still unauthorized
        }

        val authHeaders = response.headers("WWW-Authenticate")
        for (header in authHeaders) {
            if (header.startsWith("Digest", ignoreCase = true)) {
                val authHeaderValue = buildDigestHeader(header, response.request)
                if (authHeaderValue != null) {
                    return response.request.newBuilder()
                        .header("Authorization", authHeaderValue)
                        .build()
                }
            } else if (header.startsWith("Basic", ignoreCase = true)) {
                val credentials = Credentials.basic(username, password)
                return response.request.newBuilder()
                    .header("Authorization", credentials)
                    .build()
            }
        }
        return null
    }

    private fun buildDigestHeader(header: String, request: Request): String? {
        val params = parseDigestParams(header)
        val realm = params["realm"] ?: return null
        val nonce = params["nonce"] ?: return null
        val qop = params["qop"]
        val algorithm = params["algorithm"] ?: "MD5"
        val opaque = params["opaque"]

        val uri = request.url.encodedPath + (if (request.url.encodedQuery != null) "?${request.url.encodedQuery}" else "")
        val method = request.method

        val ha1 = md5Hex("$username:$realm:$password")
        val ha2 = md5Hex("$method:$uri")

        val nc = "00000001"
        val cnonce = java.util.UUID.randomUUID().toString().replace("-", "").take(16)

        val responseVal = if (qop != null && qop.contains("auth")) {
            md5Hex("$ha1:$nonce:$nc:$cnonce:auth:$ha2")
        } else {
            md5Hex("$ha1:$nonce:$ha2")
        }

        val sb = StringBuilder()
        sb.append("Digest ")
        sb.append("username=\"").append(username).append("\", ")
        sb.append("realm=\"").append(realm).append("\", ")
        sb.append("nonce=\"").append(nonce).append("\", ")
        sb.append("uri=\"").append(uri).append("\", ")
        sb.append("response=\"").append(responseVal).append("\", ")
        if (qop != null && qop.contains("auth")) {
            sb.append("qop=auth, ")
            sb.append("nc=").append(nc).append(", ")
            sb.append("cnonce=\"").append(cnonce).append("\", ")
        }
        if (opaque != null) {
            sb.append("opaque=\"").append(opaque).append("\", ")
        }
        sb.append("algorithm=").append(algorithm)

        return sb.toString()
    }

    private fun parseDigestParams(header: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        val parts = header.substringAfter("Digest ").split(",")
        for (part in parts) {
            val keyVal = part.trim().split("=", limit = 2)
            if (keyVal.size == 2) {
                val key = keyVal[0].trim()
                val value = keyVal[1].trim().removeSurrounding("\"")
                params[key] = value
            }
        }
        return params
    }

    private fun md5Hex(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
