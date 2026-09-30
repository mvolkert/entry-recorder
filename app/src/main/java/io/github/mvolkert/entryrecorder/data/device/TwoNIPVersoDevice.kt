package io.github.mvolkert.entryrecorder.data.device

import android.util.Log
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.data.model.ConnectionQuality
import io.github.mvolkert.entryrecorder.data.network.DigestAuthenticator
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
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
            .authenticator(DigestAuthenticator(deviceEntity.username, deviceEntity.password))
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
        listener.onEvent(IntercomEvent.ConnectionState(deviceEntity, ConnectionQuality.ONLINE, "Connecting to 2N IP Verso..."))

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
                listener.onEvent(IntercomEvent.ConnectionState(deviceEntity, ConnectionQuality.ONLINE, "Connected"))
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                Log.d(tag, "2N Event received: type=$type data=$data")
                handleRaw2NEvent(data, listener)
            }

            override fun onClosed(eventSource: EventSource) {
                Log.w(tag, "SSE Event Stream closed by 2N Verso. Fallback to polling if active.")
                if (isMonitoring.get()) {
                    listener.onEvent(IntercomEvent.ConnectionState(deviceEntity, ConnectionQuality.DEGRADED, "Stream closed, retrying..."))
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
                    quality = ConnectionQuality.DEGRADED,
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
                if (bothDead) {
                    consecutiveDeadPolls++
                    if (consecutiveDeadPolls == DEAD_POLLS_ALERT) {
                        listener.onEvent(
                            IntercomEvent.ConnectionState(
                                deviceEntity,
                                quality = ConnectionQuality.OFFLINE,
                                message = "Motion/noise status endpoints returned no usable data $DEAD_POLLS_ALERT times " +
                                    "— wrong path, denied auth or motion detection disabled on the device"
                            )
                        )
                    }
                } else if (consecutiveDeadPolls >= DEAD_POLLS_ALERT) {
                    // The recovery edge matters as much as the failure one: without it the device would
                    // stay reported offline in the UI long after the endpoints started answering again.
                    listener.onEvent(
                        IntercomEvent.ConnectionState(
                            deviceEntity,
                            quality = ConnectionQuality.DEGRADED,
                            message = "Motion/noise status endpoints answering again"
                        )
                    )
                    consecutiveDeadPolls = 0
                } else {
                    consecutiveDeadPolls = 0
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


