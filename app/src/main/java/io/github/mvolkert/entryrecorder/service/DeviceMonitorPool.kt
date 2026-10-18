package io.github.mvolkert.entryrecorder.service

import io.github.mvolkert.entryrecorder.data.device.IntercomDeviceFactory
import io.github.mvolkert.entryrecorder.data.model.MonitorStatus
import io.github.mvolkert.entryrecorder.data.network.HttpSnapshotClient
import io.github.mvolkert.entryrecorder.domain.device.IntercomDevice
import io.github.mvolkert.entryrecorder.domain.device.IntercomEventListener
import io.github.mvolkert.entryrecorder.domain.model.Device
import io.github.mvolkert.entryrecorder.domain.triggers.TriggerCoordinator
import io.github.mvolkert.entryrecorder.video.OnDeviceMotionAnalyzer
import io.github.mvolkert.entryrecorder.video.PreRollFrame
import io.github.mvolkert.entryrecorder.video.RecordingController
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher

/**
 * The pool of live device handles and their on-device motion analyzers, plus the diff that keeps both in step
 * with the monitored rows. Absorbs the device-map half of `IntercomMonitorService.updateMonitoredDevices`
 * verbatim — including the order-sensitive removal cleanup — so the service is left with the lifecycle work
 * only: SIP reconciliation, the lock policy, the resumed server jobs and the foreground notification.
 *
 * Built by the service rather than injected, because it needs the event listener the service *is* and the
 * scope/dispatcher pair that only exists after Hilt field injection.
 */
class DeviceMonitorPool(
    private val listener: IntercomEventListener,
    private val deviceFactory: IntercomDeviceFactory,
    private val snapshotClient: HttpSnapshotClient,
    private val monitorStatusRegistry: MonitorStatusRegistry,
    private val triggerCoordinator: TriggerCoordinator,
    private val recorder: RecordingController,
    // Reached as a lambda rather than the runner itself: a removed device's pending post-record stop has to
    // go with it, and the timer map belongs to TriggerEpisodeRunner, not to two classes.
    private val cancelPostRecordStop: (deviceId: Long) -> Unit,
    private val ioDispatcher: CoroutineDispatcher,
) {

    private val activeDevices = ConcurrentHashMap<Long, IntercomDevice>()
    private val activeMotionAnalyzers = ConcurrentHashMap<Long, OnDeviceMotionAnalyzer>()

    /**
     * Adds, replaces and removes monitored devices so the live set equals [enabledDevices], and keeps the
     * on-device motion analyzers in step with the rows that ask for them.
     */
    suspend fun sync(enabledDevices: List<Device>) {
        val currentIds = activeDevices.keys.toSet()
        val newIds = enabledDevices.map { it.id }.toSet()

        // Remove removed/disabled devices
        for (id in currentIds - newIds) {
            val device = activeDevices.remove(id)
            device?.stopMonitoring()
            monitorStatusRegistry.remove(id)
            // Per-device bookkeeping of the two maps above has to go with the device, otherwise a pending
            // post-record stop could still fire for it and a re-added id would inherit the old ring stamp.
            cancelPostRecordStop(id)
            triggerCoordinator.forgetDevice(id)
            // Snapshot health is per device id too: a re-added id must not inherit the old failure streak.
            snapshotClient.forgetDevice(id)
        }

        // Add or update active devices
        for (entity in enabledDevices) {
            val existing = activeDevices[entity.id]
            if (existing == null || existing.device != entity) {
                existing?.stopMonitoring()
                val newDevice = deviceFactory.createDevice(entity)
                activeDevices[entity.id] = newDevice
                newDevice.startMonitoring(listener)
                monitorStatusRegistry.update(entity.id, MonitorStatus.MONITORING)
            }

            // Manage the on-device (app-side) motion analyzer independently of the device implementation.
            // It has to be rebuilt when the device row changes, otherwise it keeps polling the URL and
            // credentials captured at creation time and a settings edit only takes effect after a restart.
            val existingAnalyzer = activeMotionAnalyzers[entity.id]
            if (entity.recordOnMotionOnDevice) {
                if (existingAnalyzer == null || existingAnalyzer.device != entity) {
                    existingAnalyzer?.stop()
                    val analyzer = OnDeviceMotionAnalyzer(
                        entity,
                        listener,
                        snapshotClient,
                        ioDispatcher
                    ) {
                        // Back off only for the phone's own capture loop: in PYTHON_SERVER mode the
                        // server polls the endpoint, so a server recording must not slow detection here.
                        recorder.isLocallyRecording(entity.id)
                    }
                    activeMotionAnalyzers[entity.id] = analyzer
                    analyzer.start()
                }
            } else {
                existingAnalyzer?.stop()
                activeMotionAnalyzers.remove(entity.id)
            }
        }

        // Stop analyzers for devices that were removed/disabled entirely
        for (id in activeMotionAnalyzers.keys.toSet() - newIds) {
            activeMotionAnalyzers.remove(id)?.stop()
        }
    }

    /** The row of the device with [deviceId], or null once it is no longer monitored. */
    fun deviceFor(deviceId: Long): Device? = activeDevices[deviceId]?.device

    /**
     * The frames the analyzer of [deviceId] buffered just before it confirmed motion, taken exactly once so a
     * recording covers the arrival instead of starting after it. Empty for a device without an analyzer.
     */
    fun drainPreRoll(deviceId: Long): List<PreRollFrame> =
        activeMotionAnalyzers[deviceId]?.drainPreRoll().orEmpty()

    /** True while any monitored device has a clip the phone itself is recording — the evidence a lock release needs. */
    fun anyRecordingLocally(): Boolean = activeDevices.keys.any { recorder.isLocallyRecording(it) }

    /** The live device handles, so the service's bounded teardown pass can stop every one of them at once. */
    fun liveDevices(): List<IntercomDevice> = activeDevices.values.toList()

    /** Stops and drops every analyzer; the device handles are the caller's to stop. */
    fun stopAnalyzers() {
        for (analyzer in activeMotionAnalyzers.values) {
            analyzer.stop()
        }
        activeMotionAnalyzers.clear()
    }

    fun clearDevices() {
        activeDevices.clear()
    }

    /** How many devices are monitored, which is what the foreground notification reports. */
    val deviceCount: Int get() = activeDevices.size
}
