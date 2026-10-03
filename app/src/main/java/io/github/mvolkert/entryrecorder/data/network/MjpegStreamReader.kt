package io.github.mvolkert.entryrecorder.data.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

class MjpegStreamReader(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) {
    private val tag = "MjpegStreamReader"

    sealed class StreamFrame {
        data class RawJpegFrame(val jpegBytes: ByteArray, val timestampMs: Long) : StreamFrame() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false

                other as RawJpegFrame

                if (timestampMs != other.timestampMs) return false
                if (!jpegBytes.contentEquals(other.jpegBytes)) return false

                return true
            }

            override fun hashCode(): Int {
                var result = timestampMs.hashCode()
                result = 31 * result + jpegBytes.contentHashCode()
                return result
            }
        }

    }

    /**
     * Reads continuous MJPEG multipart stream from device.mjpegUrl and yields decoded Bitmaps.
     */
    fun streamBitmaps(device: DeviceEntity): Flow<Bitmap> = flow {
        val requestBuilder = Request.Builder().url(device.mjpegUrl)
        if (device.username.isNotBlank() || device.password.isNotBlank()) {
            requestBuilder.header("Authorization", Credentials.basic(device.username, device.password))
        }

        var response: Response? = null
        try {
            response = httpClient.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful) {
                Log.w(tag, "MJPEG connection unsuccessful: HTTP ${response.code}")
                return@flow
            }
            requireMjpegContentType(response, device)

            val body = response.body
            val inputStream = BufferedInputStream(body.byteStream())
            readMjpegStream(inputStream) { jpegBytes ->
                val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                if (bitmap != null) {
                    emit(bitmap)
                }
            }
        } catch (_: CancellationException) {
            // Normal coroutine cancellation
        } catch (e: Exception) {
            Log.e(tag, "Error reading MJPEG stream for ${device.name}", e)
            // Rethrown so the collector can distinguish "the stream ended" from "the picture was fine":
            // callers fall back to snapshot polling or surface the error instead of showing nothing.
            throw e
        } finally {
            try {
                response?.close()
            } catch (_: Exception) {}
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Reads continuous MJPEG stream and yields raw JPEG byte chunks (ideal for direct disk muxing).
     */
    fun streamRawJpeg(device: DeviceEntity): Flow<ByteArray> = flow {
        val requestBuilder = Request.Builder().url(device.mjpegUrl)
        if (device.username.isNotBlank() || device.password.isNotBlank()) {
            requestBuilder.header("Authorization", Credentials.basic(device.username, device.password))
        }

        var response: Response? = null
        try {
            response = httpClient.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful) {
                throw IOException("MJPEG endpoint on ${device.name} answered HTTP ${response.code}")
            }
            requireMjpegContentType(response, device)

            val inputStream = BufferedInputStream(response.body.byteStream())
            readMjpegStream(inputStream) { jpegBytes ->
                emit(jpegBytes)
            }
        } catch (_: CancellationException) {
            // Normal cancellation
        } catch (e: Exception) {
            Log.e(tag, "Error reading raw MJPEG for ${device.name}", e)
            // Rethrown: a stream that yields nothing has to look like a failure to the recorder, which
            // falls back to snapshot polling, instead of a silently empty recording.
            throw e
        } finally {
            try {
                response?.close()
            } catch (_: Exception) {}
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Reads a continuous MJPEG multipart stream served at an arbitrary [url] (the Python server's
     * `/api/live/{id}/mjpeg`) and yields decoded Bitmaps, authenticating with an `X-API-Key` **header**
     * rather than the device's Basic auth or a `?api_key=` query — OkHttp can set headers here, unlike the
     * Coil/ExoPlayer image paths, so the key never lands in a URL/log. [label] only names the device in
     * logs. Mirrors [streamBitmaps]' failure contract (rethrow, so the caller can fall back).
     */
    fun streamBitmapsFromUrl(url: String, label: String, apiKey: String?): Flow<Bitmap> = flow {
        val requestBuilder = Request.Builder().url(url)
        if (!apiKey.isNullOrBlank()) requestBuilder.header("X-API-Key", apiKey)

        var response: Response? = null
        try {
            response = httpClient.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful) {
                throw IOException("Server live feed for $label answered HTTP ${response.code}")
            }
            val contentType = response.body.contentType()?.toString().orEmpty()
            val mainType = contentType.substringBefore(';').trim().lowercase(Locale.US)
            if (!(mainType.startsWith("multipart") || mainType.startsWith("image"))) {
                throw IOException(
                    "Server live feed for $label returned '$contentType' — not a multipart MJPEG stream"
                )
            }

            val inputStream = BufferedInputStream(response.body.byteStream())
            readMjpegStream(inputStream) { jpegBytes ->
                val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                if (bitmap != null) {
                    emit(bitmap)
                }
            }
        } catch (_: CancellationException) {
            // Normal coroutine cancellation
        } catch (e: Exception) {
            Log.e(tag, "Error reading server live MJPEG for $label", e)
            throw e
        } finally {
            try {
                response?.close()
            } catch (_: Exception) {}
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Fails when the response is not an MJPEG multipart/image stream.
     *
     * Some firmwares answer an unknown path with HTTP 200 plus an application/json error document
     * (the 2N Verso does exactly that for /api/camera/mjpeg on the deployed firmware). Scanning that
     * body for JPEG markers yields zero frames, which reads as a dead camera — so the wrong shape is
     * reported as what it is instead of streaming nothing.
     */
    private fun requireMjpegContentType(response: Response, device: DeviceEntity) {
        val contentType = response.body.contentType()?.toString().orEmpty()
        val mainType = contentType.substringBefore(';').trim().lowercase(Locale.US)
        if (mainType.startsWith("multipart") || mainType.startsWith("image")) return
        throw IOException(
            "${device.name} answered HTTP ${response.code} with '$contentType' at ${device.mjpegUrl} — " +
                "not a multipart MJPEG stream, so this path does not exist on this firmware " +
                "(use HTTP Snapshot Polling instead)"
        )
    }

    /**
     * Efficiently scans a multipart stream for JPEG SOI (0xFF, 0xD8) and EOI (0xFF, 0xD9) markers.
     * Reads in chunks (not byte-by-byte) and invokes the suspend [onFrame] directly, so it no longer
     * calls runBlocking per frame inside the collector (which could block the IO dispatcher).
     */
    private suspend fun readMjpegStream(
        inputStream: InputStream,
        onFrame: suspend (ByteArray) -> Unit
    ) {
        val buffer = ByteArrayOutputStream(64 * 1024)
        val chunk = ByteArray(16 * 1024)
        var prev = -1
        var inFrame = false

        while (true) {
            val read = withContext(Dispatchers.IO) {
                inputStream.read(chunk)
            }
            if (read == -1) break

            var i = 0
            while (i < read) {
                val b = chunk[i].toInt() and 0xFF
                i++

                if (!inFrame) {
                    if (prev == 0xFF && b == 0xD8) {
                        // Found SOI (Start of Image)
                        inFrame = true
                        buffer.reset()
                        buffer.write(0xFF)
                        buffer.write(0xD8)
                    }
                } else {
                    buffer.write(b)
                    if (prev == 0xFF && b == 0xD9) {
                        // Found EOI (End of Image)
                        inFrame = false
                        val frameBytes = buffer.toByteArray()
                        if (frameBytes.size > 100) {
                            onFrame(frameBytes)
                        }
                        buffer.reset()
                    }
                }
                prev = b
            }
        }
    }
}
