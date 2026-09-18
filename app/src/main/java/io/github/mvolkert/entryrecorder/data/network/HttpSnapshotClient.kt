package io.github.mvolkert.entryrecorder.data.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object HttpSnapshotClient {
    private const val TAG = "HttpSnapshotClient"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    /**
     * Fetches a single raw JPEG frame from the device snapshot endpoint.
     * Tries Basic auth first, then Digest if requested.
     */
    suspend fun fetchSnapshotBytes(device: DeviceEntity): ByteArray? = withContext(Dispatchers.IO) {
        val snapshotUrl = device.snapshotUrl
        try {
            val requestBuilder = Request.Builder().url(snapshotUrl)
            if (device.username.isNotBlank() || device.password.isNotBlank()) {
                requestBuilder.header("Authorization", Credentials.basic(device.username, device.password))
            }
            val response = httpClient.newCall(requestBuilder.build()).execute()
            if (response.isSuccessful) {
                response.body.bytes()
            } else {
                Log.d(TAG, "Snapshot fetch failed code=${response.code} url=$snapshotUrl")
                null
            }
        } catch (e: Exception) {
            Log.d(TAG, "Snapshot fetch exception for ${device.name}: ${e.message}")
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
