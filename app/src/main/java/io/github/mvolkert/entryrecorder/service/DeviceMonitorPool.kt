package io.github.mvolkert.entryrecorder.service

import io.github.mvolkert.entryrecorder.data.device.IntercomDeviceFactory
import io.github.mvolkert.entryrecorder.data.model.MonitorStatus
import io.github.mvolkert.entryrecorder.data.network.HttpSnapshotClient
import io.github.mvolkert.entryrecorder.domain.device.IntercomDevice
import io.github.mvolkert.entryrecorder.domain.device.IntercomEvent
import io.github.mvolkert.entryrecorder.domain.device.IntercomEventListener
import io.github.mvolkert.entryrecorder.domain.model.Device
import io.github.mvolkert.entryrecorder.domain.triggers.TriggerCoordinator
import io.github.mvolkert.entryrecorder.video.OnDeviceMotionAnalyzer
import io.github.mvolkert.entryrecorder.video.PreRollFrame
import io.github.mvolkert.entryrecorder.video.RecordingController
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher

/** Why an analyzer is being stopped, which decides whether its caller still owns the episode it started. */
internal enum class AnalyzerShutdown {
    /** The device list changed: the row is still monitored, or the device is gone but the service is not. */
    Reconcile,

    /** The service is being destroyed, so nothing is left to handle an event. */
    ServiceTeardown,
}

/**
 * Whether stopping an analyzer has to close the motion episode it started. A reconcile does, otherwise the clip
 * it armed keeps recording with no post-record stop scheduled. A service teardown does not: the scope that would
 * handle the event is already cancelled, and the clip is bounded by the max duration it armed.
 */
internal fun shouldAnnounceEpisodeEnd(episodeActive: Boolean, shutdown: AnalyzerShutdown): Boolean =
    episodeActive && shutdown == AnalyzerShutdown.Reconcile

/**
 * The pool of live device handles and their on-device motion analyzers, plus the diff that keeps both in step
 * with the monitored rows. Absorbs the device-map half of `IntercomMonitorService.updateMonitoredDevices`
 * verbatim — including the order-sensitive removal cleanup — so the service is left with the lifecycle work
 * only: SIP reconciliation, the lock policy, the resumed server jobs and the foreground notification.
 *
 * An analyzer is rebuilt only when the configuration it reads changes ([OnDeviceMotionAnalyzer.analysisKey]),
 * and every stop of one goes through [stopAnalyzer], so a device edit the analysis does not consume keeps its
 * frame reference and pre-roll ring, and a stop that lands mid-episode still closes it.
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
            // It has to be rebuilt when the configuration the analysis reads changes, otherwise it keeps polling
            // the URL and credentials captured at creation time and a settings edit only takes effect after a
            // restart. Every other field of the row is left alone, so an edit unrelated to detection neither
            // resets the frame reference nor drops an episode it was mid-way through.
            val existingAnalyzer = activeMotionAnalyzers[entity.id]
            if (entity.recordOnMotionOnDevice) {
                val key = OnDeviceMotionAnalyzer.keyFor(entity)
                if (existingAnalyzer == null || existingAnalyzer.analysisKey != key) {
                    stopAnalyzer(existingAnalyzer, AnalyzerShutdown.Reconcile)
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
                stopAnalyzer(existingAnalyzer, AnalyzerShutdown.Reconcile)
                activeMotionAnalyzers.remove(entity.id)
            }
        }

        // Stop analyzers for devices that were removed/disabled entirely
        for (id in activeMotionAnalyzers.keys.toSet() - newIds) {
            stopAnalyzer(activeMotionAnalyzers.remove(id), AnalyzerShutdown.Reconcile)
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

    /**
     * True while an in-app motion analyzer is polling. Detection happens before any trigger exists, so this is
     * the evidence the lock hold needs to keep the CPU and radio awake while the screen is off.
     */
    fun anyAnalyzerRunning(): Boolean = activeMotionAnalyzers.isNotEmpty()

    /** True when a monitored device row opted into holding the locks through idle periods. */
    fun keepLocksHeldRequested(): Boolean = activeDevices.values.any { it.device.keepLocksHeld }

    /** The live device handles, so the service's bounded teardown pass can stop every one of them at once. */
    fun liveDevices(): List<IntercomDevice> = activeDevices.values.toList()

    /** Stops and drops every analyzer without closing the episodes they started; the service is going away. */
    fun stopAnalyzers() {
        for (analyzer in activeMotionAnalyzers.values) {
            stopAnalyzer(analyzer, AnalyzerShutdown.ServiceTeardown)
        }
        activeMotionAnalyzers.clear()
    }

    /**
     * The one path every analyzer stop takes: stop it, then hand back the episode it left open. The episode flag
     * is read before [OnDeviceMotionAnalyzer.stop] resets it.
     */
    private fun stopAnalyzer(analyzer: OnDeviceMotionAnalyzer?, shutdown: AnalyzerShutdown) {
        if (analyzer == null) return
        val episodeActive = analyzer.isEpisodeActive
        analyzer.stop()
        if (shouldAnnounceEpisodeEnd(episodeActive, shutdown)) {
            listener.onEvent(IntercomEvent.MotionOnDeviceEnded(analyzer.device))
        }
    }

    fun clearDevices() {
        activeDevices.clear()
    }

    /** How many devices are monitored, which is what the foreground notification reports. */
    val deviceCount: Int get() = activeDevices.size
}
