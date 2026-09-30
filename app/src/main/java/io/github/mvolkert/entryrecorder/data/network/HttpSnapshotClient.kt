package io.github.mvolkert.entryrecorder.data.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.data.model.ConnectionQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

/**
 * Shared keep-alive client for the periodic snapshot polls (recording, motion analysis, live view).
 *
 * Reports per-device health alongside the frame itself: [consecutiveFailures] and
 * [snapshotQuality] turn "the endpoint is not answering" into data a caller can act on, instead of
 * the old single log line per failed poll that nobody watched.
 */
object HttpSnapshotClient {
    private const val TAG = "HttpSnapshotClient"

    // Only the error document shapes are rejected, so cameras serving octet-stream keep working.
    private val ERROR_BODY_SUBTYPES = setOf("json", "xml", "html", "plain")

    /** Polls that have to fail back-to-back before the path is worth calling degraded. */
    private const val DEGRADED_AFTER_POLLS = 3

    /** Back-to-back failed polls after which the endpoint counts as offline rather than flaky. */
    private const val OFFLINE_AFTER_POLLS = 10

    /** A rate estimate older than this is reported as "not measured", not as the last good number. */
    private const val RATE_STALE_MS = 5_000L

    /** Carries the per-device credentials so the shared [httpClient] can answer a Digest challenge. */
    private class SnapshotAuth(val deviceId: Long, val username: String, val password: String)

    /** Outcome of one HTTP attempt: a frame, or why the frame is missing. */
    private sealed interface FetchOutcome {
        data class Frame(val bytes: ByteArray) : FetchOutcome

        /** Timeout / connection / 5xx — the kind a single retry can recover from. */
        data object Retryable : FetchOutcome

        /** Auth rejection or a non-image body — retrying just burns another round trip. */
        data class Rejected(val reason: String) : FetchOutcome
    }

    private val consecutiveFailures = ConcurrentHashMap<Long, Int>()
    private val rateEstimates = ConcurrentHashMap<Long, Float>()
    private val lastFrameAtMs = ConcurrentHashMap<Long, Long>()

    // The last Digest challenge seen per device. A Digest-only endpoint otherwise answers every single
    // preemptive Basic header with a 401, doubling the round trips on the serialised ~6 req/s ceiling;
    // replaying the remembered challenge makes the poll one request again (a stale nonce self-heals
    // through the Authenticator, which stores the fresh challenge for the next poll).
    private val digestChallenges = ConcurrentHashMap<Long, String>()

    private val httpClient = OkHttpClient.Builder()
        // A single lost request or ACK costs one TCP RTO (~0.44 s measured on this LAN); three in a row
        // exceed the old 4 s window and silently drop a frame slot. The tight call timeout plus one
        // quick retry in fetchSnapshotBytes keeps a short RTO chain from eating the whole slot.
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS)
        // The preemptive Basic header covers Basic-only endpoints without a round trip; a Digest
        // endpoint answers that with a 401 challenge, which this authenticator resolves per device.
        .authenticator(object : Authenticator {
            override fun authenticate(route: Route?, response: Response): Request? {
                val auth = response.request.tag(SnapshotAuth::class.java) ?: return null
                val delegate = DigestAuthenticator(auth.username, auth.password)
                response.headers("WWW-Authenticate")
                    .firstOrNull(delegate::offersDigest)
                    ?.let { digestChallenges[auth.deviceId] = it }
                return delegate.authenticate(route, response)
            }
        })
        .build()

    /** Failed snapshot polls in a row for this device; 0 while the endpoint answers. */
    fun consecutiveFailures(deviceId: Long): Int = consecutiveFailures[deviceId] ?: 0

    /**
     * Snapshot frames per second actually delivered recently, measured over a rolling window.
     * 0f means "not measured": either nothing was polled, or the last frame is older than
     * [RATE_STALE_MS], so a stopped endpoint never keeps advertising the rate it had while alive.
     */
    fun achievedFps(deviceId: Long): Float {
        val lastAt = lastFrameAtMs[deviceId] ?: return 0f
        if (System.currentTimeMillis() - lastAt > RATE_STALE_MS) return 0f
        return rateEstimates[deviceId] ?: 0f
    }

    /**
     * Health of the snapshot path derived from the failure streak, so the UI can show a flaky camera
     * instead of an empty room. One or two missed polls stay ONLINE: the built-in retry covers a short
     * RTO chain, and flipping the indicator on every blip would make it noise.
     */
    fun snapshotQuality(deviceId: Long): ConnectionQuality = when (val failures = consecutiveFailures(deviceId)) {
        in 0 until DEGRADED_AFTER_POLLS -> ConnectionQuality.ONLINE
        in DEGRADED_AFTER_POLLS until OFFLINE_AFTER_POLLS -> ConnectionQuality.DEGRADED
        else -> ConnectionQuality.OFFLINE
    }

    /** Drops the health bookkeeping when a device is removed or reconfigured. */
    fun forgetDevice(deviceId: Long) {
        consecutiveFailures.remove(deviceId)
        rateEstimates.remove(deviceId)
        lastFrameAtMs.remove(deviceId)
        digestChallenges.remove(deviceId)
    }

    /**
     * Puts the credentials on the request: a remembered Digest challenge is replayed preemptively, and
     * a device with no such history (or a challenge that no longer computes) keeps the Basic fast path.
     */
    private fun withAuthHeader(device: DeviceEntity, request: Request): Request {
        if (device.username.isBlank() && device.password.isBlank()) return request
        val challenge = digestChallenges[device.id]
        if (challenge != null) {
            val digest = DigestAuthenticator(device.username, device.password)
                .preemptiveHeader(challenge, request)
            if (digest != null) return request.newBuilder().header("Authorization", digest).build()
        }
        return request.newBuilder()
            .header("Authorization", Credentials.basic(device.username, device.password))
            .build()
    }

    /**
     * Fetches a single raw JPEG frame from the device snapshot endpoint.
     * Sends preemptive Basic auth and, once a Digest challenge has been seen for that device, replays
     * Digest preemptively instead; either way a 401 is still resolved through [DigestAuthenticator]
     * using the credentials tagged onto the request.
     *
     * A non-image body is reported and dropped rather than handed back as a frame: some firmwares
     * answer a wrong or under-parameterised path with HTTP 200 plus a JSON error document, which would
     * otherwise be muxed into the recording (or silently starve motion analysis) as a "frame".
     */
    suspend fun fetchSnapshotBytes(device: DeviceEntity): ByteArray? = withContext(Dispatchers.IO) {
        var outcome = attemptFetch(device, attempt = 1)
        if (outcome is FetchOutcome.Retryable) {
            delay(150.milliseconds)
            outcome = attemptFetch(device, attempt = 2)
        }

        when (outcome) {
            is FetchOutcome.Frame -> {
                consecutiveFailures.remove(device.id)
                recordAchievedRate(device.id)
                outcome.bytes
            }
            is FetchOutcome.Rejected -> {
                countedFailure(device, outcome.reason)
                null
            }
            else -> {
                countedFailure(device, "no image after one retry")
                null
            }
        }
    }

    private suspend fun attemptFetch(device: DeviceEntity, attempt: Int): FetchOutcome =
        withContext(Dispatchers.IO) {
            val snapshotUrl = device.snapshotUrl
            try {
                val baseRequest = Request.Builder().url(snapshotUrl)
                    .tag(SnapshotAuth::class.java, SnapshotAuth(device.id, device.username, device.password))
                    .build()
                val response = httpClient.newCall(withAuthHeader(device, baseRequest)).execute()
                if (!response.isSuccessful) {
                    val reason = "HTTP ${response.code}"
                    Log.w(TAG, "Snapshot fetch failed $reason url=$snapshotUrl attempt=$attempt")
                    if (response.code == 401 || response.code == 403) {
                        // Credentials were refused even after the challenge round, so the replayed
                        // Digest scheme is not what this endpoint wants right now — drop back to Basic.
                        digestChallenges.remove(device.id)
                    }
                    // 5xx and 429 are worth the retry; a 401/403/404 will not fix itself.
                    val retryable = response.code >= 500 || response.code == 429
                    if (retryable) FetchOutcome.Retryable else FetchOutcome.Rejected(reason)
                } else {
                    val contentType = response.body.contentType()
                    if (contentType != null && contentType.type != "image" &&
                        contentType.subtype in ERROR_BODY_SUBTYPES
                    ) {
                        val reason = "content type $contentType"
                        Log.w(TAG, "Snapshot endpoint returned $reason instead of an image url=$snapshotUrl")
                        FetchOutcome.Rejected(reason)
                    } else {
                        FetchOutcome.Frame(response.body.bytes())
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Snapshot fetch exception (attempt $attempt) for ${device.name}: ${e.message}")
                FetchOutcome.Retryable
            }
        }

    private fun countedFailure(device: DeviceEntity, reason: String) {
        val failures = (consecutiveFailures[device.id] ?: 0) + 1
        consecutiveFailures[device.id] = failures
        rateEstimates.remove(device.id)
        if (failures == DEGRADED_AFTER_POLLS || failures == OFFLINE_AFTER_POLLS) {
            Log.w(TAG, "Snapshot path for ${device.name} failed $failures polls in a row ($reason)")
        }
    }

    /**
     * Rolling estimate of the delivered frame rate: the 2N serialises snapshot encoding at roughly
     * 6 req/s, so a configured rate above what the endpoint serves is unreachable by construction and
     * the achieved number is what the settings screen and the recorder log have to show.
     */
    private fun recordAchievedRate(deviceId: Long) {
        val now = System.currentTimeMillis()
        val previous = lastFrameAtMs.put(deviceId, now) ?: return
        val deltaMs = abs(now - previous)
        if (deltaMs <= 0) return
        // Exponential average over the last few frame gaps: robust against one slow poll, and it
        // settles within a couple of frames at the 1-30 fps range this endpoint works in.
        val instantaneous = 1000f / deltaMs
        val current = rateEstimates[deviceId]
        rateEstimates[deviceId] = if (current == null || current <= 0f) instantaneous
        else current * 0.7f + instantaneous * 0.3f
    }

    /**
     * Fetches snapshot and decodes it directly into a Bitmap.
     */
    suspend fun fetchSnapshotBitmap(device: DeviceEntity): Bitmap? = withContext(Dispatchers.IO) {
        val bytes = fetchSnapshotBytes(device) ?: return@withContext null
        try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode snapshot bitmap: ${e.message}")
            null
        }
    }
}
