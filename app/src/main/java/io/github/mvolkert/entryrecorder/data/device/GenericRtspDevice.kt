package io.github.mvolkert.entryrecorder.data.device

import android.util.Log
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.domain.device.IntercomDevice
import io.github.mvolkert.entryrecorder.domain.device.IntercomEvent
import io.github.mvolkert.entryrecorder.domain.device.IntercomEventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Generic RTSP / ONVIF device implementation.
 * Allows extending EntryRecorder to any standard IP camera or video doorbell.
 */
class GenericRtspDevice(
    override val deviceEntity: DeviceEntity
) : IntercomDevice {

    private val tag = "GenericRtsp_${deviceEntity.id}"

    override suspend fun testConnection(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            // Test RTSP socket connectivity
            Socket().use { socket ->
                socket.connect(InetSocketAddress(deviceEntity.ipAddress, deviceEntity.rtspPort), 5000)
                Result.success(true)
            }
        } catch (e: Exception) {
            Log.e(tag, "RTSP connection test failed", e)
            Result.failure(e)
        }
    }

    override suspend fun startMonitoring(listener: IntercomEventListener) {
        // Generic RTSP devices provide live stream; monitoring state is active
        listener.onEvent(IntercomEvent.ConnectionState(deviceEntity, true, "RTSP Ready"))
    }

    override suspend fun stopMonitoring() {
        // No persistent HTTP event connection to close for generic RTSP
    }
}
