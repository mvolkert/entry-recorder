package io.github.mvolkert.entryrecorder.domain.device

import io.github.mvolkert.entryrecorder.data.model.ConnectionCapability
import io.github.mvolkert.entryrecorder.data.model.ConnectionQuality
import io.github.mvolkert.entryrecorder.domain.model.Device
import io.github.mvolkert.entryrecorder.domain.model.SystemTimeProvider

/**
 * Something a monitored device reports. A sealed interface whose [device] is the domain model, so no
 * event carries a Room entity into a consumer. Timestamps default to [SystemTimeProvider] but are a
 * constructor parameter everywhere, so a test can pin them without touching the wall clock.
 */
sealed interface IntercomEvent {
    val device: Device

    data class MotionStarted(
        override val device: Device,
        val timestamp: Long = SystemTimeProvider.currentTimeMillis(),
    ) : IntercomEvent

    data class MotionEnded(
        override val device: Device,
        val timestamp: Long = SystemTimeProvider.currentTimeMillis(),
    ) : IntercomEvent

    data class NoiseStarted(
        override val device: Device,
        val timestamp: Long = SystemTimeProvider.currentTimeMillis(),
    ) : IntercomEvent

    data class NoiseEnded(
        override val device: Device,
        val timestamp: Long = SystemTimeProvider.currentTimeMillis(),
    ) : IntercomEvent

    // Motion detected by analyzing the video stream on-device, independent of the device's own detection
    data class MotionOnDeviceStarted(
        override val device: Device,
        val timestamp: Long = SystemTimeProvider.currentTimeMillis(),
    ) : IntercomEvent

    data class MotionOnDeviceEnded(
        override val device: Device,
        val timestamp: Long = SystemTimeProvider.currentTimeMillis(),
    ) : IntercomEvent

    data class DoorbellRung(
        override val device: Device,
        val callerNumber: String? = null,
        val timestamp: Long = SystemTimeProvider.currentTimeMillis(),
    ) : IntercomEvent

    data class ConnectionState(
        override val device: Device,
        val quality: ConnectionQuality,
        val message: String? = null,
        // Which path this report is about. Defaults to the device event stream; the snapshot pollers
        // (motion analyzer, recorder) pass SNAPSHOT so the two health signals stay separate.
        val capability: ConnectionCapability = ConnectionCapability.DEVICE_EVENTS,
        val timestamp: Long = SystemTimeProvider.currentTimeMillis(),
    ) : IntercomEvent

    data class Error(override val device: Device, val error: Throwable) : IntercomEvent
}

/**
 * Log-safe identity of an event: its class plus the device's id and name.
 *
 * Never interpolate an entity or a raw device row into a log outside this file — the stored forms carry
 * the HTTP and the SIP password, which would put both credentials in logcat on every event.
 */
fun IntercomEvent.logIdentity(): String =
    "${this::class.simpleName} from device ${device.id} (${device.name})"

interface IntercomEventListener {
    fun onEvent(event: IntercomEvent)
}

interface IntercomDevice {
    val device: Device

    suspend fun testConnection(): Result<Boolean>
    suspend fun startMonitoring(listener: IntercomEventListener)
    suspend fun stopMonitoring()

    fun getRtspStreamUri(): String = device.rtspStreamUrl
    fun getSnapshotUri(): String = device.snapshotUrl
}
