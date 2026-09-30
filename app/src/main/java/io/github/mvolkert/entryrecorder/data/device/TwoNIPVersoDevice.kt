package io.github.mvolkert.entryrecorder.data.device

import android.util.Log
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.domain.device.IntercomDevice
import io.github.mvolkert.entryrecorder.domain.device.IntercomEvent
import io.github.mvolkert.entryrecorder.domain.device.IntercomEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

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
                        // A missing or unrecognised `state` must not become "no motion": the previous decode
                        // (`?.asBoolean ?: (asString == "active")`) fabricated a MotionEnded for every payload
                        // it did not understand, so an unexpected field shape killed the trigger silently.
                        when (triState(params.get("state")) ?: triState(params.get("active"))) {
                            true -> listener.onEvent(IntercomEvent.MotionStarted(deviceEntity))
                            false -> listener.onEvent(IntercomEvent.MotionEnded(deviceEntity))
                            null -> Log.w(tag, "MotionDetected without a usable state field: ${data.take(160)}")
                        }
                    }
                    "NoiseDetected" -> {
                        // `active` is the field name the status endpoints of this API use, so accept it as an
                        // alternative spelling of the same boolean instead of guessing a new one.
                        when (triState(params.get("state")) ?: triState(params.get("active"))) {
                            true -> listener.onEvent(IntercomEvent.NoiseStarted(deviceEntity))
                            false -> listener.onEvent(IntercomEvent.NoiseEnded(deviceEntity))
                            null -> Log.w(tag, "NoiseDetected without a usable state field: ${data.take(160)}")
                        }
                    }
                    "KeyPressed" -> {
                        // Reported for every key of the device and for both edges of a press. Only the known
                        // release/hold edges are dropped: the press action string is not documented in this
                        // repo, so an unknown action still rings and duplicates of one press are collapsed by
                        // the monitor service's per-device ring debounce.
                        val action = params.get("action")?.takeIf { it.isJsonPrimitive }?.asString
                            ?.lowercase(Locale.US).orEmpty()
                        if (action in KEY_RELEASE_ACTIONS) {
                            Log.d(tag, "KeyPressed action=$action ignored (not a press edge)")
                        } else {
                            listener.onEvent(IntercomEvent.DoorbellRung(deviceEntity, callerNumber = "Doorbell Button"))
                        }
                    }
                    "CallStateChanged" -> {
                        val state = params.get("state")?.asString ?: ""
                        val direction = params.get("direction")?.asString ?: ""
                        Log.d(tag, "2N CallStateChanged: state=$state direction=$direction")
                        // "dialing" is kept on purpose: a doorbell press makes the intercom dial this phone,
                        // so from the device's point of view the ringing call is *outgoing*. Filtering by
                        // direction would therefore discard the primary ring path; the debounce upstream is
                        // what makes the overlap with KeyPressed harmless.
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
            // The fallback can only read the two boolean status endpoints; a doorbell ring has no pollable
            // endpoint here, so say so instead of letting the user assume the doorbell is broken.
            listener.onEvent(
                IntercomEvent.ConnectionState(
                    deviceEntity,
                    isConnected = true,
                    message = "SSE event stream unavailable, polling motion/noise status only. " +
                        "Doorbell rings are not delivered in this mode."
                )
            )
            var lastMotionState = false
            var lastNoiseState = false
            var consecutiveDeadPolls = 0

            while (isActive && isMonitoring.get()) {
                // A null means "this poll told us nothing" (HTTP error, unreachable, or a payload without
                // result.active) — previously that was indistinguishable from a room with no motion, because
                // the failure was swallowed and the state simply stayed false.
                val motion = pollBooleanStatus("${deviceEntity.httpBaseUrl}/api/motion/status", "Motion")
                when {
                    motion == null -> Unit
                    motion && !lastMotionState -> listener.onEvent(IntercomEvent.MotionStarted(deviceEntity))
                    !motion && lastMotionState -> listener.onEvent(IntercomEvent.MotionEnded(deviceEntity))
                    else -> Unit
                }
                if (motion != null) lastMotionState = motion

                val noise = pollBooleanStatus("${deviceEntity.httpBaseUrl}/api/noise/status", "Noise")
                when {
                    noise == null -> Unit
                    noise && !lastNoiseState -> listener.onEvent(IntercomEvent.NoiseStarted(deviceEntity))
                    !noise && lastNoiseState -> listener.onEvent(IntercomEvent.NoiseEnded(deviceEntity))
                    else -> Unit
                }
                if (noise != null) lastNoiseState = noise

                val bothDead = motion == null && noise == null
                consecutiveDeadPolls = if (bothDead) consecutiveDeadPolls + 1 else 0
                if (consecutiveDeadPolls == DEAD_POLLS_ALERT) {
                    listener.onEvent(
                        IntercomEvent.ConnectionState(
                            deviceEntity,
                            isConnected = false,
                            message = "Motion/noise status endpoints returned no usable data $DEAD_POLLS_ALERT times " +
                                "— wrong path, denied auth or motion detection disabled on the device"
                        )
                    )
                }

                delay(1500.milliseconds)
            }
        }
    }

    /** Reads a 2N status endpoint; null means "no information", never "false". */
    private suspend fun pollBooleanStatus(url: String, label: String): Boolean? {
        return try {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(tag, "$label status poll rejected: HTTP ${response.code} on $url")
                    return@use null
                }
                val body = response.body.string()
                val active = runCatching {
                    triState(
                        JsonParser.parseString(body).asJsonObject
                            .getAsJsonObject("result")
                            ?.get("active")
                    )
                }.getOrNull()
                if (active == null) {
                    Log.w(tag, "$label status poll returned no result.active from $url: ${body.take(160)}")
                }
                active
            }
        } catch (e: Exception) {
            Log.w(tag, "$label status poll error for $url: ${e.message}")
            null
        }
    }

    /**
     * Converts a 2N boolean-ish field: real booleans, the truthy/falsy words this firmware family uses
     * (`active`/`inactive`, `on`/`off`, `true`/`false`) and numbers. Null means the payload does not say,
     * which callers must handle as "no information" instead of defaulting to a definite state.
     */
    private fun triState(element: JsonElement?): Boolean? {
        val primitive = element?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
        return when {
            primitive.isBoolean -> primitive.asBoolean
            primitive.isNumber -> primitive.asDouble != 0.0
            else -> when (primitive.asString.lowercase(Locale.US)) {
                in TRUE_WORDS -> true
                in FALSE_WORDS -> false
                else -> null
            }
        }
    }

    private fun scheduleReconnect(listener: IntercomEventListener) {
        scope.launch {
            delay(5000.milliseconds)
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

    companion object {
        // Wording this firmware family uses for boolean-ish event/status fields; anything else is treated as
        // "unknown" rather than guessed, so a wrong assumption shows up in the log instead of pinning a state.
        private val TRUE_WORDS = setOf("active", "true", "on", "yes", "start", "detected", "moving")
        private val FALSE_WORDS = setOf("inactive", "false", "off", "no", "stop", "idle", "clear", "none")

        // KeyPressed edges that are NOT a doorbell press; the press itself is the default, see handleRaw2NEvent.
        private val KEY_RELEASE_ACTIONS = setOf("released", "release", "up", "long", "hold", "held", "double")

        // Report dead status endpoints once this many polls in a row, then stay quiet until recovery.
        private const val DEAD_POLLS_ALERT = 4
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
        val authHeaders = response.headers("WWW-Authenticate")
        for (header in authHeaders) {
            val candidate = when {
                header.startsWith("Digest", ignoreCase = true) -> buildDigestHeader(header, response.request)
                header.startsWith("Basic", ignoreCase = true) -> Credentials.basic(username, password)
                else -> null
            }
            if (candidate != null) {
                // Loop guard: only stop retrying if we already sent this exact credential. A stale
                // nonce yields a different Digest value, so a single legitimate retry is still allowed.
                val priorAuth = response.priorResponse?.request?.header("Authorization")
                if (priorAuth == candidate) return null
                return response.request.newBuilder()
                    .header("Authorization", candidate)
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

        val isSession = algorithm.endsWith("-sess", ignoreCase = true)
        val nc = "00000001"
        val cnonce = java.util.UUID.randomUUID().toString().replace("-", "").take(16)

        var ha1 = hashHex(algorithm, "$username:$realm:$password")
        if (isSession) ha1 = hashHex(algorithm, "$ha1:$nonce:$cnonce")
        val ha2 = hashHex(algorithm, "$method:$uri")

        val responseVal = if (qop != null && qop.contains("auth")) {
            hashHex(algorithm, "$ha1:$nonce:$nc:$cnonce:auth:$ha2")
        } else {
            hashHex(algorithm, "$ha1:$nonce:$ha2")
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

    /**
     * Parses Digest challenge parameters without naively splitting on commas: values may be quoted
     * and can themselves contain commas (e.g. certain realm/nonce strings). Handles backslash escapes
     * inside quoted values.
     */
    private fun parseDigestParams(header: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        val content = if (header.length >= 6 && header.substring(0, 6).equals("Digest", ignoreCase = true)) {
            header.substring(6)
        } else {
            header.substringAfter(' ')
        }
        var i = 0
        val n = content.length
        while (i < n) {
            while (i < n && (content[i] == ' ' || content[i] == ',' || content[i] == '\t')) i++
            val keyStart = i
            while (i < n && content[i] != '=') i++
            if (i >= n) break
            val key = content.substring(keyStart, i).trim()
            i++ // skip '='
            val value = if (i < n && content[i] == '"') {
                i++ // skip opening quote
                val sb = StringBuilder()
                while (i < n && content[i] != '"') {
                    if (content[i] == '\\' && i + 1 < n && (content[i + 1] == '"' || content[i + 1] == '\\')) {
                        sb.append(content[i + 1]); i += 2
                    } else {
                        sb.append(content[i]); i++
                    }
                }
                if (i < n) i++ // skip closing quote
                sb.toString()
            } else {
                val vStart = i
                while (i < n && content[i] != ',') i++
                content.substring(vStart, i).trim()
            }
            if (key.isNotEmpty()) params[key] = value
        }
        return params
    }

    /**
     * Computes the digest hash for the requested algorithm (RFC 7616). Supports MD5 and SHA-256
     * (including the -sess variants); falls back to MD5 for unknown algorithms so behaviour never
     * regresses on devices that only advertise MD5.
     */
    private fun hashHex(algorithm: String, input: String): String {
        val base = algorithm.removeSuffix("-sess").removeSuffix("-SESS").uppercase()
        val instance = when (base) {
            "SHA-256", "SHA256" -> "SHA-256"
            "MD5", "MD5-SESS" -> "MD5"
            else -> "MD5"
        }
        val md = try {
            MessageDigest.getInstance(instance)
        } catch (_: Exception) {
            MessageDigest.getInstance("MD5")
        }
        return md.digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
