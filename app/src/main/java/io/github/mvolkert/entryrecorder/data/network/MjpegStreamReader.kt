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
import java.io.InputStream
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
            if (!response.isSuccessful) return@flow

            val inputStream = BufferedInputStream(response.body.byteStream())
            readMjpegStream(inputStream) { jpegBytes ->
                emit(jpegBytes)
            }
        } catch (_: CancellationException) {
            // Normal cancellation
        } catch (e: Exception) {
            Log.e(tag, "Error reading raw MJPEG for ${device.name}", e)
        } finally {
            try {
                response?.close()
            } catch (_: Exception) {}
        }
    }.flowOn(Dispatchers.IO)

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
