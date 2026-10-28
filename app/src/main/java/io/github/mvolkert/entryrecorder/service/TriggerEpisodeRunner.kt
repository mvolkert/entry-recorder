package io.github.mvolkert.entryrecorder.service

import android.content.Context
import timber.log.Timber
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.data.model.MonitorStatus
import io.github.mvolkert.entryrecorder.data.repository.IntercomRepository
import io.github.mvolkert.entryrecorder.domain.alert.MotionAlert
import io.github.mvolkert.entryrecorder.domain.alert.MotionAlertTransport
import io.github.mvolkert.entryrecorder.domain.call.CallScreenLauncher
import io.github.mvolkert.entryrecorder.domain.model.Device
import io.github.mvolkert.entryrecorder.domain.triggers.TriggerCoordinator
import io.github.mvolkert.entryrecorder.notification.NotificationHelper
import io.github.mvolkert.entryrecorder.util.ConnectionLog
import io.github.mvolkert.entryrecorder.util.ConnectionSource
import io.github.mvolkert.entryrecorder.video.PreRollFrame
import io.github.mvolkert.entryrecorder.video.RecordingController
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One trigger episode's side effects: what a started or ended motion/noise event does to the status card, the
 * recording, the locks, the notifications and the remote alert — plus the post-record stop timers that decide
 * when an episode really ends.
 *
 * Split out of [IntercomMonitorService] with the bodies moved verbatim. Every duration, debounce and buffer
 * decision still comes from the pure [TriggerCoordinator]; this class is only the Android-bound half that
 * carries out those decisions, which is why it takes the service's scope and context instead of owning any.
 *
 * The log tag stays the service's own so one trigger can be followed through a single logcat stream.
 */
class TriggerEpisodeRunner(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repository: IntercomRepository,
    private val motionAlertTransport: MotionAlertTransport,
    private val recorder: RecordingController,
    private val triggerCoordinator: TriggerCoordinator,
    private val monitorStatusRegistry: MonitorStatusRegistry,
    private val callScreenLauncher: CallScreenLauncher,
    private val lockKeeper: MonitorLockKeeper,
) {

    private val tag = "IntercomMonitorService"

    // At most one pending post-record stop per device, so a new episode of the same event supersedes
    // the timer of the previous one instead of truncating the running recording.
    private val pendingPostRecordStops = ConcurrentHashMap<Long, Job>()

    /** Whether a stop is still scheduled for some device, which is what keeps a lock held through idle. */
    val hasPendingStops: Boolean get() = pendingPostRecordStops.isNotEmpty()

    /**
     * Routes a "trigger started" event: mark the device, arm the recording the trigger authorises, then
     * alert. The three start branches used to carry near-identical copies of this and drifted apart (only
     * the on-device one drained pre-roll), so the differences stay as explicit arguments here.
     */
    fun handleTriggerStarted(
        device: Device,
        eventType: EventType,
        record: Boolean,
        preRollFrames: () -> List<PreRollFrame> = { emptyList() }
    ) {
        // Motion is the trigger the live card shows as an active event; noise keeps the resting status.
        if (eventType == EventType.MOTION) monitorStatusRegistry.update(device.id, MonitorStatus.MOTION)

        // The remote alert is independent of the local wake/notification settings below (which `return` early
        // when wake is off): a phone configured to catch motion alerts gets one whether or not this phone wakes.
        if (eventType == EventType.MOTION) relayMotionAlertToRemote(device)

        cancelPostRecordStop(device.id)
        if (record) {
            val maxDurationSeconds = triggerCoordinator.triggerRecordDurationSeconds(device, eventType)
            recorder.startRecording(
                device = device,
                eventType = eventType,
                maxDurationSeconds = maxDurationSeconds,
                preRoll = preRollFrames()
            )
            lockKeeper.holdFor(triggerCoordinator.lockHoldDurationMs(maxDurationSeconds))
        }

        val wake = when (eventType) {
            EventType.MOTION -> device.wakeOnMotion
            EventType.NOISE -> device.wakeOnNoise
            EventType.RING, EventType.MANUAL -> device.wakeOnRing
            EventType.UNKNOWN -> false
        }
        if (!wake) return

        when (eventType) {
            EventType.MOTION -> NotificationHelper.showMotionNotification(context, device)
            EventType.NOISE -> NotificationHelper.showNoiseNotification(context, device)
            // The doorbell owns its richer notification and full-screen intent in the DoorbellRung branch.
            EventType.RING, EventType.MANUAL, EventType.UNKNOWN -> Unit
        }
        callScreenLauncher.launch(device, eventType)
    }

    /** Routes a "trigger ended" event: only the post-record buffer length and the log wording differ. */
    fun handleTriggerEnded(device: Device, label: String, eventType: EventType) {
        Timber.tag(tag).i("$label ended on ${device.name}")
        if (eventType == EventType.MOTION) monitorStatusRegistry.update(device.id, MonitorStatus.MONITORING)
        // Allow post-record time buffer then stop
        schedulePostRecordStop(device, eventType, triggerCoordinator.postRecordSeconds(device, eventType))
    }

    /**
     * Arms the post-record buffer for [eventType], then stops exactly the recording that event type
     * started. Cancelling any previous timer makes a flickering event (on/off/on) extend the running
     * recording rather than schedule a stop for the episode that is still being recorded — before this,
     * a trailing stop from the previous episode cut the next one short, often into a 0-byte file that
     * the recorder then discarded as "empty".
     */
    private fun schedulePostRecordStop(device: Device, eventType: EventType, seconds: Int) {
        pendingPostRecordStops.remove(device.id)?.cancel()
        pendingPostRecordStops[device.id] = scope.launch {
            delay(seconds * 1000L)
            pendingPostRecordStops.remove(device.id)
            recorder.stopRecording(device.id, eventType)
            lockKeeper.releaseIfIdle()
        }
    }

    /** Drops a pending delayed stop, because a new episode of the same event started recording. */
    fun cancelPostRecordStop(deviceId: Long) {
        pendingPostRecordStops.remove(deviceId)?.cancel()
    }

    /** Drops every scheduled stop on the way out; the service cancels the scope they would have run in. */
    fun clearPendingStops() {
        pendingPostRecordStops.clear()
    }

    /**
     * Relays a detected-motion alert to the one configured remote phone through the [MotionAlertTransport]
     * seam. Fire-and-forget on the service's scope: a push must never delay or fail the local
     * recording/notification path that follows it, and a delivery failure only logs (the recipient receiving
     * it is the device gate). Gated on the persisted settings so an unset recipient or the disabled flag
     * sends nothing.
     */
    private fun relayMotionAlertToRemote(device: Device) {
        scope.launch {
            val settings = repository.getSettings()
            if (!settings.motionAlertEnabled || settings.motionAlertRecipient.isBlank()) return@launch
            motionAlertTransport.send(
                MotionAlert(
                    serverBaseUrl = settings.serverBaseUrl,
                    apiKey = settings.serverApiKey,
                    recipient = settings.motionAlertRecipient,
                    deviceName = device.name,
                    timestampMs = System.currentTimeMillis(),
                )
            ).onFailure {
                // Same python-server wording as the control-plane client, so grepping the stream finds every
                // link to the server in one place. Fire-and-forget: a failed push never touches the local path.
                Timber.tag(tag).w(ConnectionLog.lost(ConnectionSource.PythonServer, settings.serverBaseUrl, it.message ?: "motion alert delivery failed"))
            }
        }
    }
}
