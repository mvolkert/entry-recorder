package io.github.mvolkert.entryrecorder.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import io.github.mvolkert.entryrecorder.data.device.IntercomDeviceFactory
import io.github.mvolkert.entryrecorder.data.model.ConnectionCapability
import io.github.mvolkert.entryrecorder.data.model.ConnectionQuality
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.data.model.MonitorStatus
import io.github.mvolkert.entryrecorder.data.network.HttpSnapshotClient
import io.github.mvolkert.entryrecorder.data.repository.IntercomRepository
import io.github.mvolkert.entryrecorder.di.IoDispatcher
import io.github.mvolkert.entryrecorder.domain.alert.MotionAlertTransport
import io.github.mvolkert.entryrecorder.domain.call.CallScreenLauncher
import io.github.mvolkert.entryrecorder.domain.device.IntercomEvent
import io.github.mvolkert.entryrecorder.domain.device.IntercomEventListener
import io.github.mvolkert.entryrecorder.domain.device.logIdentity
import io.github.mvolkert.entryrecorder.domain.model.Device
import io.github.mvolkert.entryrecorder.domain.triggers.TriggerCoordinator
import io.github.mvolkert.entryrecorder.notification.NotificationHelper
import io.github.mvolkert.entryrecorder.sip.SipCallManager
import io.github.mvolkert.entryrecorder.video.RecorderEvent
import io.github.mvolkert.entryrecorder.video.RecordingController
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

@AndroidEntryPoint
class IntercomMonitorService : Service(), IntercomEventListener {

    private val tag = TAG

    // Built lazily because Hilt injects the fields after the constructor returns: reading ioDispatcher
    // from a property initialiser would hit an uninitialised lateinit.
    private val serviceScope: CoroutineScope by lazy { CoroutineScope(ioDispatcher + SupervisorJob()) }

    // Pure trigger-routing policy (ring debounce + duration decisions): no Android deps, so it is built at
    // construction and unit-testable without the service.
    private val triggerCoordinator = TriggerCoordinator()

    /**
     * The three owners the monitor work was split into: the lock policy, the device/analyzer pool and one
     * trigger episode's side effects. They cross only through the two lambdas below — the pending-stop map
     * stays inside [TriggerEpisodeRunner] and the lock pair inside [MonitorLockKeeper] — so no piece of state
     * is read from two classes.
     */
    private val lockKeeper: MonitorLockKeeper by lazy {
        MonitorLockKeeper(this, serviceScope) {
            // The order the release check used to test: a pending stop first, then a running local recording.
            episodeRunner.hasPendingStops || pool.anyRecordingLocally()
        }
    }

    private val episodeRunner: TriggerEpisodeRunner by lazy {
        TriggerEpisodeRunner(
            context = this,
            scope = serviceScope,
            repository = repository,
            motionAlertTransport = motionAlertTransport,
            recorder = recorder,
            triggerCoordinator = triggerCoordinator,
            monitorStatusRegistry = monitorStatusRegistry,
            callScreenLauncher = callScreenLauncher,
            lockKeeper = lockKeeper,
        )
    }

    private val pool: DeviceMonitorPool by lazy {
        DeviceMonitorPool(
            listener = this,
            deviceFactory = deviceFactory,
            snapshotClient = snapshotClient,
            monitorStatusRegistry = monitorStatusRegistry,
            triggerCoordinator = triggerCoordinator,
            recorder = recorder,
            cancelPostRecordStop = { deviceId -> episodeRunner.cancelPostRecordStop(deviceId) },
            ioDispatcher = ioDispatcher,
        )
    }

    @Inject lateinit var repository: IntercomRepository
    @Inject lateinit var recorder: RecordingController
    @Inject lateinit var sipManager: SipCallManager
    @Inject lateinit var deviceFactory: IntercomDeviceFactory
    @Inject lateinit var snapshotClient: HttpSnapshotClient
    @Inject lateinit var monitorStatusRegistry: MonitorStatusRegistry
    @Inject lateinit var callScreenLauncher: CallScreenLauncher
    @Inject lateinit var motionAlertTransport: MotionAlertTransport
    @Inject @IoDispatcher lateinit var ioDispatcher: CoroutineDispatcher

    override fun onCreate() {
        super.onCreate()
        Log.i(tag, "IntercomMonitorService creating...")
        NotificationHelper.createNotificationChannels(this)

        // Call startForeground() before anything else: the system gives a service launched with
        // startForegroundService() only ~5s to reach startForeground(), and a slow lock acquire or SIP
        // init ahead of it triggers ForegroundServiceDidNotStartInTimeException. Show a neutral
        // "Starting…" now; the real device count follows on the first updateMonitoredDevices pass.
        val serviceNotification = NotificationHelper.buildServiceNotification(this, 0, starting = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NotificationHelper.NOTIFICATION_ID_SERVICE,
                serviceNotification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID_SERVICE, serviceNotification)
        }

        // The locks stay unheld while monitoring is idle — a trigger or a keepLocksHeld device acquires
        // them below — so nothing is taken here at service start.

        // Initialize SIP engine
        sipManager.initialize()

        // A SIP INVITE is a second, independent ring source for the doorbell (see handleSipRing).
        sipManager.onIncomingCall = { deviceId, remoteHost, caller -> handleSipRing(deviceId, remoteHost, caller) }

        // The capture loop sees the snapshot endpoint go quiet long before any event stream does, so its
        // connection observations are folded into the same routing as the device events.
        serviceScope.launch {
            recorder.events.collect { event ->
                when (event) {
                    is RecorderEvent.Connection -> onEvent(event.event)
                }
            }
        }

        // Observe devices from database and update monitoring
        serviceScope.launch {
            repository.allDevices.collect { devices ->
                updateMonitoredDevices(devices.filter { it.isEnabled })
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(tag, "IntercomMonitorService onStartCommand (action=${intent?.action})")
        // The foreground notification's Stop/Exit action routes here. stopSelf() triggers onDestroy()
        // which releases the devices, locks and SIP engine, so the user can halt monitoring without
        // force-closing the app.
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private suspend fun updateMonitoredDevices(enabledDevices: List<Device>) = withContext(ioDispatcher) {
        pool.sync(enabledDevices)

        // Reconcile the shared SIP core against the monitored set: each PBX device keeps its own account and
        // the peer-to-peer listen port follows the directly-dialled device, so adding or editing one camera no
        // longer retires another's registration. Done once per pass (not per device) so removals are relative
        // to what should still be live.
        sipManager.synchronizeDevices(enabledDevices)

        // Lock policy follows the device list: one keepLocksHeld device wins and holds through idle;
        // when the last of them goes away, anything still held is released once nothing records.
        lockKeeper.setHoldThroughIdle(enabledDevices.any { it.keepLocksHeld })

        // Re-adopt any server recordings that were running when the process died, so a reboot still shows
        // REC and auto-stops a job this client started. Idempotent, so the repeated device-list emissions
        // from the Flow above never stack a second watcher on a job already tracked live.
        recorder.resumePersistedServerRecordings(enabledDevices)

        // No camera is enabled: drop the foreground notification a running service must otherwise keep
        // showing, then end the service. The Application observer restarts it as soon as a camera is
        // enabled again; stopping here (rather than the observer calling stopService) is what keeps a
        // startForegroundService()/startForeground() pair from ever being torn apart mid-flight.
        if (pool.deviceCount == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return@withContext
        }

        // Update foreground notification with count
        val notification = NotificationHelper.buildServiceNotification(
            this@IntercomMonitorService,
            pool.deviceCount,
        )
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NotificationHelper.NOTIFICATION_ID_SERVICE, notification)
    }

    override fun onEvent(event: IntercomEvent) {
        Log.i(tag, "Received ${event.logIdentity()}")
        serviceScope.launch {
            when (event) {
                is IntercomEvent.DoorbellRung -> {
                    val device = event.device
                    Log.i(tag, "Doorbell triggered for ${device.name}")

                    // One press arrives from several sources at once (2N KeyPressed, 2N CallStateChanged and
                    // the SIP INVITE), so collapse rings per device. The first ring wins; a clip already running
                    // for another trigger folds this ring into itself instead of being replaced.
                    if (!triggerCoordinator.shouldHandleRing(device.id)) {
                        Log.i(tag, "Duplicate ring on ${device.name} within the debounce window, ignoring")
                        return@launch
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
                        this@IntercomMonitorService,
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

    /**
     * Handles a ring signalled by an incoming SIP call rather than by the 2N HTTP event stream. This keeps
     * the doorbell working in the SSE polling-fallback mode, which can only read the motion/noise status
     * endpoints and would otherwise lose every ring. Routed through the normal event path so recording,
     * notification and wake handling stay in one place, and through the same ring debounce.
     *
     * The device is named by [SipCallManager] from the call's own identity (the account it rang on, the
     * peer-to-peer caller address, or the dialed extension), so a ring behind a shared PBX is attributed to
     * the right camera rather than guessed from the monitored-device count.
     */
    private fun handleSipRing(deviceId: Long, remoteHost: String, caller: String) {
        val device = pool.deviceFor(deviceId)
        if (device == null) {
            Log.w(tag, "SIP ring (device $deviceId from $remoteHost) is no longer monitored, ignoring")
            return
        }
        onEvent(IntercomEvent.DoorbellRung(device, callerNumber = caller))
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(tag, "IntercomMonitorService onDestroy")
        serviceScope.cancel()

        // All devices are torn down at once under one deadline: this blocks the main thread either way, and
        // a serial loop made the cost grow with every camera that ignored its cancel request.
        runBlocking {
            withTimeoutOrNull(2_000.milliseconds) {
                coroutineScope {
                    pool.liveDevices().forEach { device ->
                        launch(ioDispatcher) { device.stopMonitoring() }
                    }
                }
            }
        }
        pool.clearDevices()
        pool.stopAnalyzers()
        monitorStatusRegistry.clear()

        lockKeeper.cancelSafetyRelease()
        lockKeeper.releaseAll()

        episodeRunner.clearPendingStops()
        triggerCoordinator.reset()
        sipManager.onIncomingCall = null

        sipManager.destroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "IntercomMonitorService"

        /** Action used by the persistent foreground notification to stop monitoring in place. */
        const val ACTION_STOP = "io.github.mvolkert.entryrecorder.service.ACTION_STOP"

        fun start(context: Context) {
            val intent = Intent(context, IntercomMonitorService::class.java)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                // Android 12+/14 may reject background FGS starts (e.g. from BOOT) with
                // ForegroundServiceStartNotAllowedException. Log instead of crashing the receiver.
                Log.e(TAG, "Foreground service start not allowed", e)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, IntercomMonitorService::class.java)
            context.stopService(intent)
        }
    }
}
