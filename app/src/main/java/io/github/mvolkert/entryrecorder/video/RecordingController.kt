package io.github.mvolkert.entryrecorder.video

import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.data.model.EventType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the recorder offers its consumers, as a JVM-only contract: [RtspStreamRecorder] is the single
 * implementation, but the Live ViewModel and the monitor service code against this interface so a unit
 * test can drive both without Media3, a Context or a camera.
 *
 * Default values for the optional arguments live here, not on the implementation — Kotlin forbids an
 * override from redeclaring them.
 */
interface RecordingController {

    /** Device ids that currently have an active recording (local or on the server). */
    val activeDeviceIds: StateFlow<Set<Long>>

    /** One-shot recorder observations for the monitor service to route (never replayed to a late collector). */
    val events: Flow<RecorderEvent>

    /** True only while the *phone's* capture loop polls the device's snapshot endpoint (not server recordings). */
    fun isLocallyRecording(deviceId: Long): Boolean

    /** Start a recording for [device] triggered by [eventType]; folds into a running clip if one exists. */
    fun startRecording(
        device: DeviceEntity,
        eventType: EventType,
        maxDurationSeconds: Int = 60,
        preRoll: List<PreRollFrame> = emptyList()
    )

    /** Stops an ongoing recording; [reason] restricts the stop to the recording that event type started. */
    fun stopRecording(deviceId: Long, reason: EventType? = null)

    /** Rebuilds the app's view of server recordings that were in flight when the process died. */
    suspend fun resumePersistedServerRecordings(devices: List<DeviceEntity>)
}
