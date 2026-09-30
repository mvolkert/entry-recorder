package io.github.mvolkert.entryrecorder.data.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.util.concurrent.TimeUnit

object HttpSnapshotClient {
    private const val TAG = "HttpSnapshotClient"

    // Only the error document shapes are rejected, so cameras serving octet-stream keep working.
    private val ERROR_BODY_SUBTYPES = setOf("json", "xml", "html", "plain")

    /**
     * Carries the per-device credentials on the request so the shared [httpClient] (one singleton for
     * every device) can answer a Digest challenge with the right user/password. Retrieved from
     * `response.request.tag(...)` inside the authenticator below.
     */
    private class SnapshotAuth(val username: String, val password: String)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        // The preemptive Basic header below covers Basic-only endpoints without a round trip; a Digest
        // endpoint answers that with a 401 challenge, which this authenticator then resolves per device.
        .authenticator(object : Authenticator {
            override fun authenticate(route: Route?, response: Response): Request? {
                val auth = response.request.tag(SnapshotAuth::class.java) ?: return null
                return DigestAuthenticator(auth.username, auth.password).authenticate(route, response)
            }
        })
        .build()

    /**
     * Fetches a single raw JPEG frame from the device snapshot endpoint.
     * Sends preemptive Basic auth and, if the endpoint challenges with Digest, resolves it through
     * [DigestAuthenticator] using the credentials tagged onto the request.
     *
     * A non-image body is reported and dropped rather than handed back as a frame: some firmwares
     * answer a wrong or under-parameterised path with HTTP 200 plus a JSON error document, which would
     * otherwise be muxed into the recording (or silently starve motion analysis) as a "frame".
     */
    suspend fun fetchSnapshotBytes(device: DeviceEntity): ByteArray? = withContext(Dispatchers.IO) {
        val snapshotUrl = device.snapshotUrl
        try {
            val requestBuilder = Request.Builder().url(snapshotUrl)
                .tag(SnapshotAuth::class.java, SnapshotAuth(device.username, device.password))
            if (device.username.isNotBlank() || device.password.isNotBlank()) {
                requestBuilder.header("Authorization", Credentials.basic(device.username, device.password))
            }
            val response = httpClient.newCall(requestBuilder.build()).execute()
            if (response.isSuccessful) {
                val contentType = response.body.contentType()
                if (contentType != null && contentType.type != "image" &&
                    contentType.subtype in ERROR_BODY_SUBTYPES
                ) {
                    Log.w(TAG, "Snapshot endpoint returned $contentType instead of an image url=$snapshotUrl")
                    null
                } else {
                    response.body.bytes()
                }
            } else {
                Log.w(TAG, "Snapshot fetch failed code=${response.code} url=$snapshotUrl")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Snapshot fetch exception for ${device.name}: ${e.message}")
            null
        }
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
