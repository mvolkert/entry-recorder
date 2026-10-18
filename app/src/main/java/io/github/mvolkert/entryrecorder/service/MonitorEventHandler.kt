package io.github.mvolkert.entryrecorder.service

import android.content.Context
import android.util.Log
import io.github.mvolkert.entryrecorder.data.model.ConnectionCapability
import io.github.mvolkert.entryrecorder.data.model.ConnectionQuality
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.data.model.MonitorStatus
import io.github.mvolkert.entryrecorder.domain.call.CallScreenLauncher
import io.github.mvolkert.entryrecorder.domain.device.IntercomEvent
import io.github.mvolkert.entryrecorder.domain.triggers.TriggerCoordinator
import io.github.mvolkert.entryrecorder.notification.NotificationHelper
import io.github.mvolkert.entryrecorder.video.RecordingController

/**
 * What each device event does: the ring fan-out, the motion/noise episodes and the connection-health bookkeeping.
 *
 * Extracted from [IntercomMonitorService], which keeps the `Service` itself — lifecycle, `startForeground`, the
 * listener registration and the scope every event is dispatched in. This class owns no scope and launches nothing:
 * [handle] runs on the caller's coroutine, so the service's `serviceScope` stays the only lifetime that bounds
 * monitoring work. The log tag stays the service's own so one event can be followed through a single logcat stream.
 */
class MonitorEventHandler(
    private val context: Context,
    private val triggerCoordinator: TriggerCoordinator,
    private val lockKeeper: MonitorLockKeeper,
    private val episodeRunner: TriggerEpisodeRunner,
    private val pool: DeviceMonitorPool,
    private val recorder: RecordingController,
    private val monitorStatusRegistry: MonitorStatusRegistry,
    private val callScreenLauncher: CallScreenLauncher,
) {
    private val tag = "IntercomMonitorService"

    /** Routes one device event. Called from the service's own coroutine, on the worker dispatcher. */
    suspend fun handle(event: IntercomEvent) {
        when (event) {
            is IntercomEvent.DoorbellRung -> {
                val device = event.device
                Log.i(tag, "Doorbell triggered for ${device.name}")

                // One press arrives from several sources at once (2N KeyPressed, 2N CallStateChanged and
                // the SIP INVITE), so collapse rings per device. The first ring wins; a clip already running
                // for another trigger folds this ring into itself instead of being replaced.
                if (!triggerCoordinator.shouldHandleRing(device.id)) {
                    Log.i(tag, "Duplicate ring on ${device.name} within the debounce window, ignoring")
                    return
                }

                // 1. Start Recording if configured, or fold the ring into the clip that is already running.
                // This deliberately does not cancel the device's pending post-record stop: that timer belongs
                // to the motion/noise clip, and stopRecording refuses to end a clip filed under RING.
                // An active ring holds the CPU and radio awake for its full clip plus the stop margin.
                lockKeeper.holdFor(triggerCoordinator.lockHoldDurationMs(triggerCoordinator.ringRecordDurationSeconds(device)))
                if (device.recordOnRing) {
                    recorder.startRecording(
                        device = device,
                        eventType = EventType.RING,
                        maxDurationSeconds = triggerCoordinator.ringRecordDurationSeconds(device)
                    )
                }

                // 2. Notify (honoring the ring alert settings). wakeOnRing is the single
                // "bring the call to the screen" switch: off, the ring stays a plain notification and
                // no full-screen intent is attached, so the lockscreen is never taken over.
                NotificationHelper.showDoorbellNotification(
                    context,
                    device,
                    event.callerNumber,
                    playSound = device.soundOnRing,
                    vibrate = device.vibrateOnRing,
                    fullScreen = device.wakeOnRing
                )

                // 3. Launch IncomingCallActivity directly for immediate lockscreen display.
                // NOTE (targetSdk 36+): background Activity Launch is restricted, so this
                // direct startActivity is best-effort. With wakeOnRing on, the full-screen intent
                // attached above is the reliable path to the call screen.
                if (device.wakeOnRing) {
                    callScreenLauncher.launch(device, EventType.RING, event.callerNumber ?: "2N IP Verso")
                }
            }

            is IntercomEvent.MotionStarted -> {
                Log.i(tag, "Motion started on ${event.device.name}")
                episodeRunner.handleTriggerStarted(
                    device = event.device,
                    eventType = EventType.MOTION,
                    record = event.device.recordOnMotion,
                )
            }

            is IntercomEvent.MotionOnDeviceStarted -> {
                Log.i(tag, "On-device motion analysis started on ${event.device.name}")
                episodeRunner.handleTriggerStarted(
                    device = event.device,
                    eventType = EventType.MOTION,
                    record = event.device.recordOnMotionOnDevice,
                    // Prepend the frames the analyzer saw just before it confirmed motion, so the
                    // recording covers the arrival rather than starting ~1s after it.
                    preRollFrames = { pool.drainPreRoll(event.device.id) },
                )
            }

            is IntercomEvent.NoiseStarted -> {
                Log.i(tag, "Noise started on ${event.device.name}")
                episodeRunner.handleTriggerStarted(
                    device = event.device,
                    eventType = EventType.NOISE,
                    record = event.device.recordOnNoise,
                )
            }

            is IntercomEvent.MotionEnded ->
                episodeRunner.handleTriggerEnded(device = event.device, label = "Motion", eventType = EventType.MOTION)

            is IntercomEvent.MotionOnDeviceEnded -> episodeRunner.handleTriggerEnded(
                device = event.device,
                label = "On-device motion analysis",
                eventType = EventType.MOTION,
            )

            is IntercomEvent.NoiseEnded ->
                episodeRunner.handleTriggerEnded(device = event.device, label = "Noise", eventType = EventType.NOISE)

            is IntercomEvent.ConnectionState -> {
                val detail = "Device ${event.device.name} connection: ${event.quality} (${event.message})"
                // Degraded monitoring (e.g. the 2N polling fallback that cannot see doorbells at all)
                // has to be visible in logcat and on the live card, not only in logcat.
                if (event.quality == ConnectionQuality.ONLINE) Log.i(tag, detail) else Log.w(tag, detail)
                // Keep the two delivery paths tracked separately so the live card and Settings can
                // say which one is down: a dead event stream only breaks the camera-driven triggers,
                // a dead snapshot path only breaks live/recording/in-app analysis. The overall status
                // dot follows the snapshot path specifically — it is what the live view, recordings
                // and in-app motion all depend on — so a dead event stream no longer marks an
                // otherwise-working camera offline. Camera-event health is shown on the trigger glyphs.
                when (event.capability) {
                    ConnectionCapability.DEVICE_EVENTS ->
                        monitorStatusRegistry.updateEventQuality(event.device.id, event.quality)
                    ConnectionCapability.SNAPSHOT -> {
                        monitorStatusRegistry.updateSnapshotQuality(event.device.id, event.quality)
                        applyConnectionQuality(event.device.id, event.quality)
                    }
                }
            }

            is IntercomEvent.Error -> {
                Log.e(tag, "Device ${event.device.name} error: ${event.error.message}")
            }
        }
    }

    /**
     * Maps the snapshot-path connection quality onto the device's overall [MonitorStatus] so the live
     * card dot can show it. Only the snapshot path drives this (see the ConnectionState handler); a
     * degraded or offline camera event stream is surfaced separately, on the trigger glyphs.
     *
     * A recovery must not erase a running motion alert, so ONLINE only replaces MONITORING; a degraded
     * or offline source cannot deliver trustworthy events at all, so it always wins over MOTION.
     */
    private fun applyConnectionQuality(deviceId: Long, quality: ConnectionQuality) {
        val target = when (quality) {
            ConnectionQuality.ONLINE ->
                if (monitorStatusRegistry.statusFor(deviceId) == MonitorStatus.MOTION) null else MonitorStatus.MONITORING
            ConnectionQuality.DEGRADED -> MonitorStatus.DEGRADED
            ConnectionQuality.OFFLINE -> MonitorStatus.OFFLINE
        } ?: return
        monitorStatusRegistry.update(deviceId, target)
    }
}
