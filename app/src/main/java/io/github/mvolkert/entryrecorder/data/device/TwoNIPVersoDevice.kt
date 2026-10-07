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

    // The status polls share the authenticated transport but must never inherit the SSE stream's
    // indefinite read timeout: one silent endpoint would otherwise park the whole fallback loop.
    private val pollClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .callTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    /** Camera motion is only worth asking the device about while a trigger consumes it. */
    private val motionEventRequested: Boolean
        get() = deviceEntity.recordOnMotion || deviceEntity.wakeOnMotion

    /** Same rule for noise; in-app analysis rides the snapshot path and never sets either flag. */
    private val noiseEventRequested: Boolean
        get() = deviceEntity.recordOnNoise || deviceEntity.wakeOnNoise

    /**
     * Event classes the device is asked for, one rule shared by the SSE subscription and the polling
     * fallback so a transport swap inherits the gate instead of re-learning it. A ring is always wanted:
     * the button is the whole point of the device and it has no pollable status endpoint.
     */
    private fun requestedEventNames(): List<String> = buildList {
        add("KeyPressed")
        add("CallStateChanged")
        if (motionEventRequested) add("MotionDetected")
        if (noiseEventRequested) add("NoiseDetected")
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
        val sseUrl = "${deviceEntity.httpBaseUrl}/api/event/subscribe?events=${requestedEventNames().joinToString(",")}"
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
                Log.w(tag, "SSE Event Stream closed by 2N Verso.")
                if (isMonitoring.get()) {
                    listener.onEvent(IntercomEvent.ConnectionState(deviceEntity, ConnectionQuality.DEGRADED, "Stream closed, retrying..."))
                    scheduleReconnect(listener)
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                Log.w(tag, "SSE Failure (${response?.code}): ${t?.message}. Checking the camera triggers for a polling fallback.")
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

        val wantsMotion = motionEventRequested
        val wantsNoise = noiseEventRequested
        if (!wantsMotion && !wantsNoise) {
            // Neither camera trigger is asked for, so there is nothing to poll and nothing is degraded:
            // rings arrive over the SIP INVITE and in-app motion analysis rides the snapshot path.
            Log.i(tag, "HTTP event polling not started — no camera motion/noise trigger requested")
            return
        }
        val polledLabels = listOfNotNull("motion".takeIf { wantsMotion }, "noise".takeIf { wantsNoise })
            .joinToString("/")

        pollingJob = scope.launch {
            Log.i(tag, "Starting 2N HTTP polling fallback loop ($polledLabels)")
            // The fallback can only read boolean status endpoints; a doorbell ring has no pollable
            // endpoint here, so say so instead of letting the user assume the doorbell is broken.
            listener.onEvent(
                IntercomEvent.ConnectionState(
                    deviceEntity,
                    quality = ConnectionQuality.DEGRADED,
                    message = "SSE event stream unavailable, polling $polledLabels status only. " +
                        "Doorbell rings are not delivered in this mode."
                )
            )

            val pollers = buildList {
                if (wantsMotion) add(
                    StatusPoller(
                        label = "Motion",
                        url = "${deviceEntity.httpBaseUrl}/api/motion/status",
                        started = { IntercomEvent.MotionStarted(it) },
                        ended = { IntercomEvent.MotionEnded(it) },
                    )
                )
                if (wantsNoise) add(
                    StatusPoller(
                        label = "Noise",
                        url = "${deviceEntity.httpBaseUrl}/api/noise/status",
                        started = { IntercomEvent.NoiseStarted(it) },
                        ended = { IntercomEvent.NoiseEnded(it) },
                    )
                )
            }
            var deadReported = false

            while (isActive && isMonitoring.get()) {
                val now = System.currentTimeMillis()
                var polledAny = false
                for (poller in pollers) {
                    if (!poller.dueAt(now)) continue
                    polledAny = true
                    when (val outcome = pollStatusEndpoint(poller)) {
                        is StatusPoll.Known -> {
                            poller.deadStreak = 0
                            poller.absent = false
                            poller.nextProbeAtMs = 0L
                            val edge = when {
                                outcome.active && !poller.lastActive -> poller.started(deviceEntity)
                                !outcome.active && poller.lastActive -> poller.ended(deviceEntity)
                                else -> null
                            }
                            poller.lastActive = outcome.active
                            edge?.let(listener::onEvent)
                        }

                        StatusPoll.Unusable -> poller.deadStreak++

                        // A `code 2 invalid request path` answer is permanent for this firmware, so
                        // re-asking every 1.5 s is pure load on the serial endpoint: back off to a slow
                        // re-probe instead. Lowering the log level would not be a fix — the request is the cost.
                        StatusPoll.Absent -> {
                            poller.absent = true
                            poller.nextProbeAtMs = now + DEAD_ENDPOINT_REPROBE_MS
                        }
                    }
                }

                val allGone = pollers.isNotEmpty() && pollers.all { it.gone() }
                if (allGone && !deadReported) {
                    deadReported = true
                    val endpointsWord = if (pollers.size == 1) "endpoint has" else "endpoints have"
                    listener.onEvent(
                        IntercomEvent.ConnectionState(
                            deviceEntity,
                            quality = ConnectionQuality.OFFLINE,
                            message = "${pollers.joinToString("/") { it.label.lowercase(Locale.US) }} status " +
                                "$endpointsWord returned no usable data — wrong path, denied auth or camera " +
                                "detection disabled"
                        )
                    )
                } else if (deadReported && !allGone) {
                    // The recovery edge matters as much as the failure one: without it the device would
                    // stay reported offline in the UI long after the endpoints started answering again.
                    deadReported = false
                    listener.onEvent(
                        IntercomEvent.ConnectionState(
                            deviceEntity,
                            quality = ConnectionQuality.DEGRADED,
                            message = "Polled status endpoints answering again ($polledLabels)"
                        )
                    )
                }

                delay((if (polledAny) POLL_INTERVAL_MS else DEAD_ENDPOINT_REPROBE_MS).milliseconds)
            }
        }
    }

    /** Outcome of one status-endpoint read, keeping "the device says no" apart from "we learned nothing". */
    private sealed interface StatusPoll {
        data class Known(val active: Boolean) : StatusPoll
        /** HTTP error, unreachable, or a payload without `result.active` — no information, may recover. */
        data object Unusable : StatusPoll

        /** The endpoint does not exist on this firmware; asking again quickly changes nothing. */
        data object Absent : StatusPoll
    }

    /**
     * Per-endpoint bookkeeping so the motion and noise paths share one polling implementation. Exists
     * only for a trigger the user asked for, which is what keeps an unrequested endpoint out of both the
     * request load and the connection report.
     */
    private class StatusPoller(
        val label: String,
        val url: String,
        val started: (DeviceEntity) -> IntercomEvent,
        val ended: (DeviceEntity) -> IntercomEvent,
    ) {
        var lastActive = false
        var nextProbeAtMs = 0L
        var deadStreak = 0
        var absent = false

        fun dueAt(now: Long): Boolean = now >= nextProbeAtMs

        fun gone(): Boolean = absent || deadStreak >= DEAD_POLLS_ALERT
    }

    /** Reads a 2N status endpoint; [StatusPoll.Unusable] means "no information", never "inactive". */
    private suspend fun pollStatusEndpoint(poller: StatusPoller): StatusPoll {
        return try {
            val request = Request.Builder().url(poller.url).get().build()
            pollClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    if (response.code == HTTP_NOT_FOUND) {
                        Log.i(tag, "${poller.label} status endpoint not served (HTTP 404): ${poller.url}")
                        return@use StatusPoll.Absent
                    }
                    Log.w(tag, "${poller.label} status poll rejected: HTTP ${response.code} on ${poller.url}")
                    return@use StatusPoll.Unusable
                }
                val body = response.body.string()
                if (isInvalidRequestPath(body)) {
                    Log.i(tag, "${poller.label} status endpoint does not exist on this firmware: ${body.take(120)}")
                    return@use StatusPoll.Absent
                }
                val active = runCatching {
                    triState(
                        JsonParser.parseString(body).asJsonObject
                            .getAsJsonObject("result")
                            ?.get("active")
                    )
                }.getOrNull()
                if (active == null) {
                    Log.w(tag, "${poller.label} status poll returned no result.active from ${poller.url}: ${body.take(160)}")
                    StatusPoll.Unusable
                } else {
                    StatusPoll.Known(active)
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "${poller.label} status poll error for ${poller.url}: ${e.message}")
            StatusPoll.Unusable
        }
    }

    /**
     * 2N answers a path this firmware does not serve with HTTP 200 +
     * `{"success":false,"error":{"code":2,"description":"invalid request path"}}` instead of a 404.
     */
    private fun isInvalidRequestPath(body: String): Boolean = runCatching {
        val root = JsonParser.parseString(body).asJsonObject
        if (root.get("success")?.takeIf { it.isJsonPrimitive }?.asBoolean != false) return@runCatching false
        root.get("error")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("code")?.takeIf { it.isJsonPrimitive }?.asInt == ERROR_CODE_INVALID_REQUEST_PATH
    }.getOrDefault(false)

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

        // Status polls are cheap when they answer, so an endpoint that works is asked often …
        private const val POLL_INTERVAL_MS = 1500L

        // … while one that answered "invalid request path" — a permanent verdict for this firmware — only
        // gets a slow re-probe instead of ≈40 requests an hour that can never return anything.
        private const val DEAD_ENDPOINT_REPROBE_MS = 5 * 60 * 1000L

        // 2N's `error.code` for a path the device does not serve; it comes back with HTTP 200.
        private const val ERROR_CODE_INVALID_REQUEST_PATH = 2

        private const val HTTP_NOT_FOUND = 404
    }
}


