package io.github.mvolkert.entryrecorder.domain.device

import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity

sealed class IntercomEvent {
    data class MotionStarted(val device: DeviceEntity, val timestamp: Long = System.currentTimeMillis()) : IntercomEvent()
    data class MotionEnded(val device: DeviceEntity, val timestamp: Long = System.currentTimeMillis()) : IntercomEvent()
    data class NoiseStarted(val device: DeviceEntity, val timestamp: Long = System.currentTimeMillis()) : IntercomEvent()
    data class NoiseEnded(val device: DeviceEntity, val timestamp: Long = System.currentTimeMillis()) : IntercomEvent()
    // Motion detected by analyzing the video stream on-device, independent of the device's own detection
    data class MotionOnDeviceStarted(val device: DeviceEntity, val timestamp: Long = System.currentTimeMillis()) : IntercomEvent()
    data class MotionOnDeviceEnded(val device: DeviceEntity, val timestamp: Long = System.currentTimeMillis()) : IntercomEvent()
    data class DoorbellRung(val device: DeviceEntity, val callerNumber: String? = null, val timestamp: Long = System.currentTimeMillis()) : IntercomEvent()
    data class CallState(val device: DeviceEntity, val state: String, val timestamp: Long = System.currentTimeMillis()) : IntercomEvent()
    data class ConnectionState(val device: DeviceEntity, val isConnected: Boolean, val message: String? = null) : IntercomEvent()
    data class Error(val device: DeviceEntity, val error: Throwable) : IntercomEvent()
}

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
