package io.github.mvolkert.entryrecorder.data.device

import android.util.Log
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.domain.model.Device
import io.github.mvolkert.entryrecorder.data.model.ConnectionQuality
import io.github.mvolkert.entryrecorder.data.network.DigestAuthenticator
import io.github.mvolkert.entryrecorder.data.network.withCancellableResponse
import io.github.mvolkert.entryrecorder.domain.device.IntercomDevice
import io.github.mvolkert.entryrecorder.domain.device.IntercomEvent
import io.github.mvolkert.entryrecorder.domain.device.IntercomEventListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

/**
 * The 2N IP Verso's real event mechanism: the **Logging API**.
 *
 * Constructed per device by [IntercomDeviceFactory] rather than by Hilt, because a device instance belongs
 * to one `device`. The two OkHttp profiles and the IO dispatcher come from the graph, so every
 * watched camera shares one connection pool instead of each opening its own.
 *
 * The device has no SSE on this firmware (the `/api/event` endpoints answer `invalid request path`), so events are
 * obtained by subscribing a temporary log channel, draining it on a poll interval, and re-subscribing
 * before its duration lapses.
 */
class TwoNIPVersoDevice(
    override val device: Device,
    eventStreamClient: OkHttpClient,
    snapshotPollClient: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher,
    parentScope: CoroutineScope,
) : IntercomDevice {

    private val tag = "2N_Verso_${device.id}"

    // The device's work is a child of the application scope, so a process teardown cannot leave the
    // reconnect loop running, while stopMonitoring can still cancel this device on its own.
    private val parentJob: Job = parentScope.coroutineContext[Job] ?: SupervisorJob()

    // Owns the subscribe/pull loop, and is created only when something is actually launched into it: an
    // instance built just to answer testConnection — which is what the device form's connection probe does —
    // must not leave a child Job attached to the application scope that nothing will ever cancel. It is a
    // var because tearing a device down cancels it for good.
    private var scope: CoroutineScope? = null
    private val isMonitoring = AtomicBoolean(false)

    // The Logging API channel this device currently holds, and when it must be re-acquired. A pull does not
    // extend the channel, so the loop re-subscribes before [channelExpiresAtMs] instead of after a lapse.
    @Volatile
    private var channelId: String? = null
    @Volatile
    private var channelExpiresAtMs = 0L

    // Whether the current failure has already been surfaced, so a stubborn endpoint is reported once rather
    // than re-flooded into the UI on every retry.
    @Volatile
    private var transportReportedDown = false

    private fun newScope() = CoroutineScope(ioDispatcher + SupervisorJob(parentJob))

    /**
     * The live scope for this device's monitoring work, rebuilt when a previous [stopMonitoring] cancelled
     * the old one — a device that gets watched again must not reconnect and poll into a dead job forever.
     */
    private fun monitoringScope(): CoroutineScope =
        scope?.takeIf { it.isActive } ?: newScope().also { scope = it }

    // Digest / Basic authentication support, bound to this device's credentials. Derived from the shared
    // stream profile so the connection pool is common but the timeouts stay this device's own. There is no
    // long-lived stream anymore, so the read timeout is finite: a silent request must fail, not park.
    private val client: OkHttpClient by lazy {
        eventStreamClient.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .authenticator(DigestAuthenticator(device.username, device.password))
            .retryOnConnectionFailure(true)
            .build()
    }

    // The drains share the authenticated transport but keep the short snapshot timeout, so one slow pull
    // cannot hold the loop longer than a snapshot would have.
    private val pollClient: OkHttpClient by lazy {
        snapshotPollClient.newBuilder()
            .authenticator(DigestAuthenticator(device.username, device.password))
            .build()
    }

    /** Camera motion is only worth asking the device about while a trigger consumes it. */
    private val motionEventRequested: Boolean
        get() = device.recordOnMotion || device.wakeOnMotion

    /** Same rule for noise; in-app analysis rides the snapshot path and never sets either flag. */
    private val noiseEventRequested: Boolean
        get() = device.recordOnNoise || device.wakeOnNoise

    /**
     * Event classes the subscribe filter asks the device for. The same one rule that gated the old poll now
     * gates the Logging subscription, so a transport swap inherits the gate instead of re-learning it: motion
     * only while a motion trigger is on, noise only while a noise trigger is on. Ring and call-state are
     * always listed so a subscription opened for camera reasons still routes a doorbell press it drains.
     */
    private fun requestedEventNames(): List<String> = buildList {
        add("KeyPressed")
        add("CallStateChanged")
        if (motionEventRequested) add("MotionDetected")
        if (noiseEventRequested) add("NoiseDetected")
    }

    override suspend fun testConnection(): Result<Boolean> = withContext(ioDispatcher) {
        try {
            val url = "${device.httpBaseUrl}/api/system/info"
            val request = Request.Builder().url(url).get().build()
            client.withCancellableResponse(request) { response ->
                if (response.isSuccessful) {
                    Result.success(true)
                } else {
                    // Try fallback info endpoint
                    val fallbackUrl = "${device.httpBaseUrl}/api/info/status"
                    val fallbackReq = Request.Builder().url(fallbackUrl).get().build()
                    client.withCancellableResponse(fallbackReq) { fbResponse ->
                        if (fbResponse.isSuccessful) Result.success(true)
                        else Result.failure(IOException("HTTP ${response.code}: ${response.message}"))
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(tag, "Connection test failed", e)
            Result.failure(e)
        }
    }

    override suspend fun startMonitoring(listener: IntercomEventListener) = withContext(ioDispatcher) {
        if (isMonitoring.getAndSet(true)) return@withContext

        Log.i(tag, "Starting 2N IP Verso monitoring on ${device.ipAddress} (FW 2.50+, Logging API)")

        // The Logging subscription exists to carry camera-reported motion and noise, the two events with no
        // on-device fallback. A ring needs no HTTP subscription at all: it arrives over the SIP INVITE, and
        // in-app motion analysis rides the snapshot path. So, exactly as the old poll gate, ask for nothing
        // when nothing is consumed — and report no connection state, since there is no path to be down.
        if (!motionEventRequested && !noiseEventRequested) {
            Log.i(tag, "Logging API subscription not started — no camera motion/noise trigger requested")
            return@withContext
        }

        listener.onEvent(
            IntercomEvent.ConnectionState(
                device,
                ConnectionQuality.ONLINE,
                "Connecting to 2N IP Verso (Logging API)...",
            )
        )
        startLoggingPollLoop(requestedEventNames(), listener)
    }

    /**
     * Hold one log channel, drain it on an interval, and re-subscribe before its duration lapses.
     *
     * The whole transport runs on the monitor session's scope, so [stopMonitoring] cancelling that scope is
     * enough to stop it; the loop also stops itself once [isMonitoring] is cleared.
     */
    private fun startLoggingPollLoop(eventNames: List<String>, listener: IntercomEventListener) {
        channelId = null
        channelExpiresAtMs = 0L

        monitoringScope().launch {
            var subscribeFailures = 0
            while (isActive && isMonitoring.get()) {
                // Re-acquire the channel before it expires: a pull never extends it, so an expired id would
                // only ever answer "no such channel".
                if (channelId == null || System.currentTimeMillis() >= channelExpiresAtMs) {
                    when (val sub = subscribe(eventNames)) {
                        is SubscribeResult.Ok -> {
                            channelId = sub.channelId
                            channelExpiresAtMs = System.currentTimeMillis() +
                                (LOG_CHANNEL_DURATION_S - LOG_RESUBSCRIBE_MARGIN_S) * 1000L
                            subscribeFailures = 0
                            reportRecovered(listener)
                        }

                        is SubscribeResult.Failed -> {
                            channelId = null
                            subscribeFailures++
                            reportSubscribeFailure(sub, subscribeFailures, listener)
                            delay(if (sub.permanent) DEAD_ENDPOINT_REPROBE_MS else LOG_SUBSCRIBE_RETRY_MS)
                            continue
                        }
                    }
                }

                val id = channelId ?: continue
                when (pull(id, listener)) {
                    PullResult.Drained -> Unit
                    // The channel went away between the expiry check and this pull: drop it so the next
                    // iteration re-subscribes rather than draining a dead id forever.
                    PullResult.Gone -> channelId = null
                    PullResult.Retryable -> Unit
                }

                delay(LOG_PULL_INTERVAL_MS.milliseconds)
            }
        }
    }

    /** Result of opening a Logging API channel. */
    private sealed interface SubscribeResult {
        data class Ok(val channelId: String) : SubscribeResult
        data class Failed(val httpStatus: Int?, val code2N: Int?, val permanent: Boolean) : SubscribeResult
    }

    /** Result of one channel drain. */
    private enum class PullResult { Drained, Gone, Retryable }

    /**
     * POST the subscribe endpoint with the requested filter and return the channel id from `result.id`.
     *
     * The verb and body shape are provisional until task 3.2/3.3 confirm them against real hardware; the
     * documented form is `POST /api/log/subscribe?filter=…&include=new&duration=…`.
     */
    private suspend fun subscribe(eventNames: List<String>): SubscribeResult = try {
        val filter = eventNames.joinToString(",")
        val url = "${device.httpBaseUrl}/api/log/subscribe" +
            "?filter=$filter&include=new&duration=$LOG_CHANNEL_DURATION_S"
        val request = Request.Builder()
            .url(url)
            .post(ByteArray(0).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.withCancellableResponse(request) { response ->
            val body = response.body.string()
            val root = parseObjectOrNull(body)
            if (!response.isSuccessful || root == null) {
                val code = extract2NErrorCode(root)
                return@withCancellableResponse SubscribeResult.Failed(response.code, code, permanent = isPermanentReject(response.code, code))
            }
            val id = root.getAsJsonObjectOrNull("result")
                ?.get("id")?.takeIf { it.isJsonPrimitive }?.asString
            if (id.isNullOrBlank()) {
                val code = extract2NErrorCode(root)
                SubscribeResult.Failed(response.code, code, permanent = isPermanentReject(response.code, code))
            } else {
                SubscribeResult.Ok(id)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(tag, "Logging subscribe error: ${e.message}")
        SubscribeResult.Failed(null, null, permanent = false)
    }

    /** Drain the channel's queue and route each log entry through the shared decode. */
    private suspend fun pull(channel: String, listener: IntercomEventListener): PullResult = try {
        val url = "${device.httpBaseUrl}/api/log/pull?channel=$channel"
        val request = Request.Builder().url(url).get().build()
        pollClient.withCancellableResponse(request) { response ->
            val body = response.body.string()
            if (response.code == HTTP_NOT_FOUND || isInvalidRequestPath(body)) {
                return@withCancellableResponse PullResult.Gone
            }
            if (!response.isSuccessful) return@withCancellableResponse PullResult.Retryable
            val root = parseObjectOrNull(body) ?: return@withCancellableResponse PullResult.Retryable
            dispatch2NEvents(root, listener)
            PullResult.Drained
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(tag, "Logging pull error: ${e.message}")
        PullResult.Retryable
    }

    /** Release the channel. Best-effort and non-blocking: teardown must not wait on the device. */
    private fun fireUnsubscribe(channel: String) {
        val url = "${device.httpBaseUrl}/api/log/unsubscribe"
        val body = JsonObject().apply { addProperty("id", channel) }.toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(url).post(body).build()
        try {
            client.newCall(request).enqueue(
                object : Callback {
                    override fun onFailure(call: okhttp3.Call, e: IOException) {
                        Log.d(tag, "Logging unsubscribe skipped: ${e.message}")
                    }

                    override fun onResponse(call: okhttp3.Call, response: Response) {
                        response.close()
                    }
                }
            )
        } catch (e: Exception) {
            Log.d(tag, "Logging unsubscribe could not be issued: ${e.message}")
        }
    }

    private fun reportSubscribeFailure(failed: SubscribeResult.Failed, attempt: Int, listener: IntercomEventListener) {
        val message = when (failed.code2N) {
            ERROR_CODE_INVALID_CONNECTION_TYPE ->
                "2N Logging API needs HTTPS (device refused the HTTP connection, code $ERROR_CODE_INVALID_CONNECTION_TYPE)"
            ERROR_CODE_UNAUTHORIZED ->
                "2N Logging API denied: enable the Logging service for this API user (code $ERROR_CODE_UNAUTHORIZED)"
            ERROR_CODE_INVALID_REQUEST_PATH ->
                "2N Logging API is not served on this firmware (code $ERROR_CODE_INVALID_REQUEST_PATH)"
            else ->
                "2N Logging API unavailable (HTTP ${failed.httpStatus ?: '-'}, attempt $attempt)"
        }
        Log.w(tag, message)
        if (!transportReportedDown) {
            transportReportedDown = true
            listener.onEvent(
                IntercomEvent.ConnectionState(device, ConnectionQuality.OFFLINE, message)
            )
        }
    }

    private fun reportRecovered(listener: IntercomEventListener) {
        if (transportReportedDown) {
            transportReportedDown = false
            listener.onEvent(
                IntercomEvent.ConnectionState(device, ConnectionQuality.ONLINE, "Connected")
            )
        }
    }

    /**
     * Fan a Logging API response out to [decodeEventObject].
     *
     * The `pull` envelope is provisional (see the Open Question in the change design): the entries array is
     * looked for under `result`/`data` and the usual 2N names, and an unrecognised shape is logged rather
     * than silently dropped, so the hardware gate can read the real path straight out of logcat.
     */
    private fun dispatch2NEvents(root: JsonObject, listener: IntercomEventListener) {
        val entries = extractEventElements(root)
        if (entries.isEmpty()) {
            Log.d(tag, "No event array in 2N Logging payload: ${root.toString().take(160)}")
            return
        }
        for (element in entries) {
            element.takeIf { it.isJsonObject }?.asJsonObject?.let { decodeEventObject(it, listener) }
        }
    }

    private fun extractEventElements(root: JsonObject): List<JsonElement> {
        val scopes = listOfNotNull(
            root,
            root.getAsJsonObjectOrNull("result"),
            root.getAsJsonObjectOrNull("data"),
        )
        for (obj in scopes) {
            for (key in EVENT_ARRAY_KEYS) {
                val el = obj.get(key)
                if (el != null && el.isJsonArray) return el.asJsonArray.asList()
            }
        }
        // A single event object (the shape the old SSE delivered) still decodes, so the reused handler works
        // for both transports.
        val single = root.getAsJsonObjectOrNull("event")
            ?: if (root.has("event") || root.has("type")) root else null
        return listOfNotNull(single)
    }

    private fun decodeEventObject(eventObj: JsonObject, listener: IntercomEventListener) {
        val eventType = eventObj.get("event")?.takeIf { it.isJsonPrimitive }?.asString
            ?: eventObj.get("type")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
        val params = eventObj.getAsJsonObjectOrNull("params") ?: JsonObject()

        when (eventType) {
            "MotionDetected" -> {
                // A missing or unrecognised `state` must not become "no motion": the previous decode
                // (`?.asBoolean ?: (asString == "active")`) fabricated a MotionEnded for every payload
                // it did not understand, so an unexpected field shape killed the trigger silently.
                when (triState(params.get("state")) ?: triState(params.get("active"))) {
                    true -> listener.onEvent(IntercomEvent.MotionStarted(device))
                    false -> listener.onEvent(IntercomEvent.MotionEnded(device))
                    null -> Log.w(tag, "MotionDetected without a usable state field: ${eventObj.toString().take(160)}")
                }
            }
            "NoiseDetected" -> {
                // `active` is the field name the status endpoints of this API use, so accept it as an
                // alternative spelling of the same boolean instead of guessing a new one.
                when (triState(params.get("state")) ?: triState(params.get("active"))) {
                    true -> listener.onEvent(IntercomEvent.NoiseStarted(device))
                    false -> listener.onEvent(IntercomEvent.NoiseEnded(device))
                    null -> Log.w(tag, "NoiseDetected without a usable state field: ${eventObj.toString().take(160)}")
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
                    listener.onEvent(IntercomEvent.DoorbellRung(device, callerNumber = "Doorbell Button"))
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
                    listener.onEvent(IntercomEvent.DoorbellRung(device, callerNumber = params.get("peer")?.asString))
                }
            }
        }
    }

    /**
     * 2N answers a path this firmware does not serve, or a request it rejects, with HTTP 200 +
     * `{"success":false,"error":{"code":N,"description":"..."}}` instead of an error status.
     */
    private fun isInvalidRequestPath(body: String): Boolean =
        extract2NErrorCode(parseObjectOrNull(body)) == ERROR_CODE_INVALID_REQUEST_PATH

    /** The 2N `error.code` for a request it refused, or null if the body carries none. */
    private fun extract2NErrorCode(root: JsonObject?): Int? = root
        ?.getAsJsonObjectOrNull("error")
        ?.get("code")?.takeIf { it.isJsonPrimitive }?.asInt

    /**
     * A reject that will keep happening, so the loop should back off long and report once: an unregistered
     * path (code 2), a wrong connection type (code 7), or denied auth (code 9) are configuration, not blips.
     */
    private fun isPermanentReject(httpStatus: Int, code2N: Int?): Boolean =
        code2N in setOf(ERROR_CODE_INVALID_REQUEST_PATH, ERROR_CODE_INVALID_CONNECTION_TYPE, ERROR_CODE_UNAUTHORIZED) ||
            httpStatus == HTTP_NOT_FOUND

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

    /**
     * Idempotent and non-suspending on purpose: the service tears devices down concurrently under a
     * deadline, so a second call must not race the first, and work that is already cancelled has to keep
     * running to completion instead of being cut short by the deadline itself.
     */
    override suspend fun stopMonitoring() {
        if (isMonitoring.getAndSet(false)) {
            Log.i(tag, "2N monitoring stopped")
        }
        val channel = channelId
        channelId = null
        if (channel != null) fireUnsubscribe(channel)
        // Cancels the pull loop along with the scope's own job. Dropped afterwards so the cancelled job
        // leaves the application scope's children; a device that is watched again gets a fresh one.
        scope?.cancel()
        scope = null
    }

    private fun parseObjectOrNull(body: String): JsonObject? =
        runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()

    private fun JsonObject.getAsJsonObjectOrNull(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    companion object {
        // Wording this firmware family uses for boolean-ish event/status fields; anything else is treated as
        // "unknown" rather than guessed, so a wrong assumption shows up in the log instead of pinning a state.
        private val TRUE_WORDS = setOf("active", "true", "on", "yes", "start", "detected", "moving")
        private val FALSE_WORDS = setOf("inactive", "false", "off", "no", "stop", "idle", "clear", "none")

        // KeyPressed edges that are NOT a doorbell press; the press itself is the default, see decodeEventObject.
        private val KEY_RELEASE_ACTIONS = setOf("released", "release", "up", "long", "hold", "held", "double")

        // Where the drained log entries may sit in the pull body; checked in order until one is an array.
        private val EVENT_ARRAY_KEYS = listOf("log", "logs", "entries", "events", "items")

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        // Subscribe for a fixed window and re-acquire this many seconds before it lapses, so a pull never
        // races the device's own channel expiry.
        private const val LOG_CHANNEL_DURATION_S = 90L
        private const val LOG_RESUBSCRIBE_MARGIN_S = 15L

        // Drain often enough to feel live but well under the Verso's ≈5.9 req/s serial ceiling.
        private const val LOG_PULL_INTERVAL_MS = 2000L

        // Backoff when the Logging API refuses the account or transport (code 7/9): a configuration gate, not
        // a blip, so retry slowly and let the operator fix it.
        private const val LOG_SUBSCRIBE_RETRY_MS = 10_000L

        // … while an endpoint that answered "invalid request path" — a permanent verdict for this firmware —
        // only gets a slow re-probe instead of ≈40 requests an hour that can never return anything.
        private const val DEAD_ENDPOINT_REPROBE_MS = 5 * 60 * 1000L

        // 2N `error.code` values seen on this firmware family.
        private const val ERROR_CODE_INVALID_REQUEST_PATH = 2
        private const val ERROR_CODE_INVALID_CONNECTION_TYPE = 7
        private const val ERROR_CODE_UNAUTHORIZED = 9

        private const val HTTP_NOT_FOUND = 404
    }
}
