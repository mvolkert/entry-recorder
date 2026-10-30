package io.github.mvolkert.entryrecorder.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import timber.log.Timber
import dagger.hilt.android.AndroidEntryPoint
import io.github.mvolkert.entryrecorder.data.device.IntercomDeviceFactory
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

    private val eventHandler: MonitorEventHandler by lazy {
        MonitorEventHandler(
            context = this,
            triggerCoordinator = triggerCoordinator,
            lockKeeper = lockKeeper,
            episodeRunner = episodeRunner,
            pool = pool,
            recorder = recorder,
            monitorStatusRegistry = monitorStatusRegistry,
            callScreenLauncher = callScreenLauncher,
        )
    }

    /**
     * The single consumer of [eventHandler]: every device event waits its turn here, so a started event and
     * the ended event that follows it cannot be handled concurrently on the service's multi-threaded IO
     * dispatcher. Everything that funnels through [onEvent] (the device poll loops, the motion analyzers, the
     * recorder's connection observations, a SIP ring) inherits that ordering.
     */
    private val eventQueue: MonitorEventQueue by lazy {
        MonitorEventQueue(serviceScope) { event -> eventHandler.handle(event) }
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
        Timber.tag(tag).i("IntercomMonitorService creating...")
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
        Timber.tag(tag).d("IntercomMonitorService onStartCommand (action=${intent?.action})")
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

    /** Every device callback lands here; the queue orders them and [MonitorEventHandler] routes each one. */
    override fun onEvent(event: IntercomEvent) {
        Timber.tag(tag).i("Received ${event.logIdentity()}")
        eventQueue.submit(event)
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
            Timber.tag(tag).w("SIP ring (device $deviceId from $remoteHost) is no longer monitored, ignoring")
            return
        }
        onEvent(IntercomEvent.DoorbellRung(device, callerNumber = caller))
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.tag(tag).i("IntercomMonitorService onDestroy")
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
        private const val TAG = MONITOR_LOG_TAG

        /** Action used by the persistent foreground notification to stop monitoring in place. */
        const val ACTION_STOP = "io.github.mvolkert.entryrecorder.service.ACTION_STOP"

        fun start(context: Context) {
            val intent = Intent(context, IntercomMonitorService::class.java)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                // Android 12+/14 may reject background FGS starts (e.g. from BOOT) with
                // ForegroundServiceStartNotAllowedException. Log instead of crashing the receiver.
                Timber.tag(TAG).e(e, "Foreground service start not allowed")
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, IntercomMonitorService::class.java)
            context.stopService(intent)
        }
    }
}
