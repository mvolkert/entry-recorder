package io.github.mvolkert.entryrecorder.domain.device

import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.data.model.ConnectionCapability
import io.github.mvolkert.entryrecorder.data.model.ConnectionQuality

sealed class IntercomEvent {
    abstract val device: DeviceEntity

    data class MotionStarted(override val device: DeviceEntity, val timestamp: Long = System.currentTimeMillis()) : IntercomEvent()
    data class MotionEnded(override val device: DeviceEntity, val timestamp: Long = System.currentTimeMillis()) : IntercomEvent()
    data class NoiseStarted(override val device: DeviceEntity, val timestamp: Long = System.currentTimeMillis()) : IntercomEvent()
    data class NoiseEnded(override val device: DeviceEntity, val timestamp: Long = System.currentTimeMillis()) : IntercomEvent()
    // Motion detected by analyzing the video stream on-device, independent of the device's own detection
    data class MotionOnDeviceStarted(override val device: DeviceEntity, val timestamp: Long = System.currentTimeMillis()) : IntercomEvent()
    data class MotionOnDeviceEnded(override val device: DeviceEntity, val timestamp: Long = System.currentTimeMillis()) : IntercomEvent()
    data class DoorbellRung(override val device: DeviceEntity, val callerNumber: String? = null, val timestamp: Long = System.currentTimeMillis()) : IntercomEvent()
    data class ConnectionState(
        override val device: DeviceEntity,
        val quality: ConnectionQuality,
        val message: String? = null,
        // Which path this report is about. Defaults to the device event stream; the snapshot pollers
        // (motion analyzer, recorder) pass SNAPSHOT so the two health signals stay separate.
        val capability: ConnectionCapability = ConnectionCapability.DEVICE_EVENTS
    ) : IntercomEvent()
    data class Error(override val device: DeviceEntity, val error: Throwable) : IntercomEvent()
}

/**
 * Log-safe identity of an event: its class plus the device's id and name.
 *
 * Never interpolate the event or the [DeviceEntity] themselves into a log — the entity's `toString()`
 * carries the HTTP and the SIP password, which would put both credentials in logcat on every event.
 */
fun IntercomEvent.logIdentity(): String =
    "${this::class.simpleName} from device ${device.id} (${device.name})"

interface IntercomEventListener {
    fun onEvent(event: IntercomEvent)
}

interface IntercomDevice {
    val deviceEntity: DeviceEntity

    suspend fun testConnection(): Result<Boolean>
    suspend fun startMonitoring(listener: IntercomEventListener)
    suspend fun stopMonitoring()
    
    fun getRtspStreamUri(): String = deviceEntity.rtspStreamUrl
    fun getSnapshotUri(): String = deviceEntity.snapshotUrl
}
