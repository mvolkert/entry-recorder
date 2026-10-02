package io.github.mvolkert.entryrecorder.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import io.github.mvolkert.entryrecorder.EntryRecorderApp
import io.github.mvolkert.entryrecorder.data.device.IntercomDeviceFactory
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.data.model.ConnectionCapability
import io.github.mvolkert.entryrecorder.data.model.ConnectionQuality
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.data.model.MonitorStatus
import io.github.mvolkert.entryrecorder.data.network.HttpSnapshotClient
import io.github.mvolkert.entryrecorder.domain.device.IntercomDevice
import io.github.mvolkert.entryrecorder.domain.device.IntercomEvent
import io.github.mvolkert.entryrecorder.domain.device.IntercomEventListener
import io.github.mvolkert.entryrecorder.notification.NotificationHelper
import io.github.mvolkert.entryrecorder.ui.incoming.IncomingCallActivity
import io.github.mvolkert.entryrecorder.video.OnDeviceMotionAnalyzer
import io.github.mvolkert.entryrecorder.video.PreRollFrame
import io.github.mvolkert.entryrecorder.video.RecorderEvent
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

class IntercomMonitorService : Service(), IntercomEventListener {

    private val tag = "IntercomMonitorService"
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val activeDevices = ConcurrentHashMap<Long, IntercomDevice>()
    private val activeMotionAnalyzers = ConcurrentHashMap<Long, OnDeviceMotionAnalyzer>()
    // At most one pending post-record stop per device, so a new episode of the same event supersedes
    // the timer of the previous one instead of truncating the running recording.
    private val pendingPostRecordStops = ConcurrentHashMap<Long, Job>()
    // Last handled doorbell ring per device, in wall-clock ms, for the duplicate-ring debounce.
    private val lastRingHandledAt = ConcurrentHashMap<Long, Long>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val app by lazy { application as EntryRecorderApp }
    private val repository by lazy { app.repository }
    private val recorder by lazy { app.recorder }
    private val sipManager by lazy { app.sipCallManager }

    override fun onCreate() {
        super.onCreate()
        Log.i(tag, "IntercomMonitorService creating...")
        NotificationHelper.createNotificationChannels(this)

        acquireWakeAndWifiLocks()

        // Start as Foreground Service immediately
        // startForeground must post a notification before the first device emission lands, so show a
        // neutral "Starting…" rather than a momentary "Monitoring 0 devices" flash; the count follows
        // on the next updateMonitoredDevices pass.
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

        // Initialize SIP engine
        sipManager.initialize()

        // A SIP INVITE is a second, independent ring source for the doorbell (see handleSipRing).
        sipManager.onIncomingCall = { remoteHost, caller -> handleSipRing(remoteHost, caller) }

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

    private suspend fun updateMonitoredDevices(enabledDevices: List<DeviceEntity>) = withContext(Dispatchers.IO) {
        val currentIds = activeDevices.keys.toSet()
        val newIds = enabledDevices.map { it.id }.toSet()

        // Remove removed/disabled devices
        for (id in currentIds - newIds) {
            val device = activeDevices.remove(id)
            device?.stopMonitoring()
            MonitorStatusHolder.remove(id)
            // Per-device bookkeeping of the two maps below has to go with the device, otherwise a pending
            // post-record stop could still fire for it and a re-added id would inherit the old ring stamp.
            pendingPostRecordStops.remove(id)?.cancel()
            lastRingHandledAt.remove(id)
            // Snapshot health is per device id too: a re-added id must not inherit the old failure streak.
            HttpSnapshotClient.forgetDevice(id)
        }

        // Add or update active devices
        for (entity in enabledDevices) {
            val existing = activeDevices[entity.id]
            if (existing == null || existing.deviceEntity != entity) {
                existing?.stopMonitoring()
                val newDevice = IntercomDeviceFactory.createDevice(entity)
                activeDevices[entity.id] = newDevice
                newDevice.startMonitoring(this@IntercomMonitorService)
                MonitorStatusHolder.update(entity.id, MonitorStatus.MONITORING)

                // Configure SIP for device
                sipManager.configureDeviceSip(entity)
            }

            // Manage the on-device (app-side) motion analyzer independently of the device implementation.
            // It has to be rebuilt when the device row changes, otherwise it keeps polling the URL and
            // credentials captured at creation time and a settings edit only takes effect after a restart.
            val existingAnalyzer = activeMotionAnalyzers[entity.id]
            if (entity.recordOnMotionOnDevice) {
                if (existingAnalyzer == null || existingAnalyzer.deviceEntity != entity) {
                    existingAnalyzer?.stop()
                    val analyzer = OnDeviceMotionAnalyzer(entity, this@IntercomMonitorService) {
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

        // No camera is enabled: leave the foreground entirely so the persistent notification a running
        // foreground service must show disappears, rather than reading "Monitoring 0 devices". The
        // Application observer restarts this service as soon as a camera is enabled again.
        if (activeDevices.isEmpty()) {
            stopSelf()
            return@withContext
        }

        // Update foreground notification with count
        val notification = NotificationHelper.buildServiceNotification(
            this@IntercomMonitorService,
            activeDevices.size
        )
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NotificationHelper.NOTIFICATION_ID_SERVICE, notification)
    }

    override fun onEvent(event: IntercomEvent) {
        Log.i(tag, "Received IntercomEvent: $event")
        serviceScope.launch {
            when (event) {
                is IntercomEvent.DoorbellRung -> {
                    val device = event.device
                    Log.i(tag, "Doorbell triggered for ${device.name}")

                    // One press arrives from several sources at once (2N KeyPressed, 2N CallStateChanged and
                    // the SIP INVITE), so collapse rings per device. The first ring wins; the recording itself
                    // is additionally guarded by RtspStreamRecorder's isRecording check.
                    val ringAt = System.currentTimeMillis()
                    val previousRingAt = lastRingHandledAt.put(device.id, ringAt)
                    if (previousRingAt != null && ringAt - previousRingAt < RING_DEBOUNCE_MS) {
                        Log.i(tag, "Duplicate ring on ${device.name} ${ringAt - previousRingAt}ms after the previous one, ignoring")
                        return@launch
                    }

                    // 1. Start Recording if configured
                    cancelPostRecordStop(device.id)
                    if (device.recordOnRing) {
                        recorder.startRecording(
                            device = device,
                            eventType = EventType.RING,
                            maxDurationSeconds = device.ringRecordSeconds
                        )
                    }

                    // 2. Wake lockscreen & notify (honoring the ring alert settings)
                    NotificationHelper.showDoorbellNotification(
                        this@IntercomMonitorService,
                        device,
                        event.callerNumber,
                        playSound = device.soundOnRing,
                        vibrate = device.vibrateOnRing
                    )

                    // 3. Launch IncomingCallActivity directly for immediate lockscreen display.
                    // NOTE (targetSdk 36+): background Activity Launch is restricted, so this
                    // direct startActivity is best-effort. The doorbell path stays reliable because
                    // NotificationHelper attaches a full-screen intent to the ring notification.
                    if (device.wakeOnRing) {
                        startActivity(callActivityIntent(device, EventType.RING, event.callerNumber ?: "2N IP Verso"))
                    }
                }

                is IntercomEvent.MotionStarted -> {
                    Log.i(tag, "Motion started on ${event.device.name}")
                    handleTriggerStarted(event.device, EventType.MOTION, record = event.device.recordOnMotion)
                }

                is IntercomEvent.MotionOnDeviceStarted -> {
                    Log.i(tag, "On-device motion analysis started on ${event.device.name}")
                    handleTriggerStarted(
                        device = event.device,
                        eventType = EventType.MOTION,
                        record = event.device.recordOnMotionOnDevice,
                        // Prepend the frames the analyzer saw just before it confirmed motion, so the
                        // recording covers the arrival rather than starting ~1s after it.
                        preRollFrames = { activeMotionAnalyzers[event.device.id]?.drainPreRoll().orEmpty() }
                    )
                }

                is IntercomEvent.NoiseStarted -> {
                    Log.i(tag, "Noise started on ${event.device.name}")
                    handleTriggerStarted(event.device, EventType.NOISE, record = event.device.recordOnNoise)
                }

                is IntercomEvent.MotionEnded -> handleTriggerEnded(event.device, "Motion", EventType.MOTION)

                is IntercomEvent.MotionOnDeviceEnded ->
                    handleTriggerEnded(event.device, "On-device motion analysis", EventType.MOTION)

                is IntercomEvent.NoiseEnded -> handleTriggerEnded(event.device, "Noise", EventType.NOISE)

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
                            MonitorStatusHolder.updateEventQuality(event.device.id, event.quality)
                        ConnectionCapability.SNAPSHOT -> {
                            MonitorStatusHolder.updateSnapshotQuality(event.device.id, event.quality)
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
                if (MonitorStatusHolder.statusFor(deviceId) == MonitorStatus.MOTION) null else MonitorStatus.MONITORING
            ConnectionQuality.DEGRADED -> MonitorStatus.DEGRADED
            ConnectionQuality.OFFLINE -> MonitorStatus.OFFLINE
        } ?: return
        MonitorStatusHolder.update(deviceId, target)
    }

    /**
     * Routes a "trigger started" event: mark the device, arm the recording the trigger authorises, then
     * alert. The three start branches used to carry near-identical copies of this and drifted apart (only
     * the on-device one drained pre-roll), so the differences stay as explicit arguments here.
     */
    private fun handleTriggerStarted(
        device: DeviceEntity,
        eventType: EventType,
        record: Boolean,
        preRollFrames: () -> List<PreRollFrame> = { emptyList() }
    ) {
        // Motion is the trigger the live card shows as an active event; noise keeps the resting status.
        if (eventType == EventType.MOTION) MonitorStatusHolder.update(device.id, MonitorStatus.MOTION)

        cancelPostRecordStop(device.id)
        if (record) {
            recorder.startRecording(
                device = device,
                eventType = eventType,
                maxDurationSeconds = postRecordSecondsFor(device, eventType) + TRIGGER_RECORD_HEADROOM_SECONDS,
                preRoll = preRollFrames()
            )
        }

        val wake = when (eventType) {
            EventType.MOTION -> device.wakeOnMotion
            EventType.NOISE -> device.wakeOnNoise
            EventType.RING, EventType.MANUAL -> device.wakeOnRing
        }
        if (!wake) return

        when (eventType) {
            EventType.MOTION -> NotificationHelper.showMotionNotification(this@IntercomMonitorService, device)
            EventType.NOISE -> NotificationHelper.showNoiseNotification(this@IntercomMonitorService, device)
            // The doorbell owns its richer notification and full-screen intent in the DoorbellRung branch.
            EventType.RING, EventType.MANUAL -> Unit
        }
        startActivity(callActivityIntent(device, eventType))
    }

    /** Routes a "trigger ended" event: only the post-record buffer length and the log wording differ. */
    private fun handleTriggerEnded(device: DeviceEntity, label: String, eventType: EventType) {
        Log.i(tag, "$label ended on ${device.name}")
        if (eventType == EventType.MOTION) MonitorStatusHolder.update(device.id, MonitorStatus.MONITORING)
        // Allow post-record time buffer then stop
        schedulePostRecordStop(device, eventType, postRecordSecondsFor(device, eventType))
    }

    private fun postRecordSecondsFor(device: DeviceEntity, eventType: EventType): Int = when (eventType) {
        EventType.MOTION -> device.motionPostRecordSeconds
        EventType.NOISE -> device.noisePostRecordSeconds
        EventType.RING, EventType.MANUAL -> device.ringRecordSeconds
    }

    /** Full-screen call/preview activity for [device]; only a ring carries a caller to display. */
    private fun callActivityIntent(device: DeviceEntity, eventType: EventType, caller: String? = null) =
        Intent(this@IntercomMonitorService, IncomingCallActivity::class.java).apply {
            putExtra(IncomingCallActivity.EXTRA_DEVICE_ID, device.id)
            putExtra(IncomingCallActivity.EXTRA_EVENT_TYPE, eventType.name)
            caller?.let { putExtra(IncomingCallActivity.EXTRA_CALLER, it) }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

    /**
     * Handles a ring signalled by an incoming SIP call rather than by the 2N HTTP event stream. This keeps
     * the doorbell working in the SSE polling-fallback mode, which can only read the motion/noise status
     * endpoints and would otherwise lose every ring. Routed through the normal event path so recording,
     * notification and wake handling stay in one place, and through the same ring debounce.
     */
    private fun handleSipRing(remoteHost: String, caller: String) {
        val devices = activeDevices.values.map { it.deviceEntity }
        val device = devices.firstOrNull { it.ipAddress == remoteHost }
            ?: devices.singleOrNull()?.also {
                Log.i(tag, "SIP ring from $remoteHost attributed to the only monitored device ${it.name}")
            }
        if (device == null) {
            Log.w(tag, "SIP ring from $remoteHost matches none of the ${devices.size} monitored devices, ignoring")
            return
        }
        onEvent(IntercomEvent.DoorbellRung(device, callerNumber = caller))
    }

    /**
     * Arms the post-record buffer for [eventType], then stops exactly the recording that event type
     * started. Cancelling any previous timer makes a flickering event (on/off/on) extend the running
     * recording rather than schedule a stop for the episode that is still being recorded — before this,
     * a trailing stop from the previous episode cut the next one short, often into a 0-byte file that
     * the recorder then discarded as "empty".
     */
    private fun schedulePostRecordStop(device: DeviceEntity, eventType: EventType, seconds: Int) {
        pendingPostRecordStops.remove(device.id)?.cancel()
        pendingPostRecordStops[device.id] = serviceScope.launch {
            delay((seconds * 1000L).milliseconds)
            pendingPostRecordStops.remove(device.id)
            recorder.stopRecording(device.id, eventType)
        }
    }

    /** Drops a pending delayed stop, because a new episode of the same event started recording. */
    private fun cancelPostRecordStop(deviceId: Long) {
        pendingPostRecordStops.remove(deviceId)?.cancel()
    }

    private fun acquireWakeAndWifiLocks() {
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "EntryRecorder::MonitorWakeLock"
            ).apply {
                acquire(24 * 60 * 60 * 1000L) // 24h
            }

            val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            wifiLock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // API 29+ exposes a tag-only factory; the int-mode overload is deprecated.
                wifiManager.createWifiLock("EntryRecorder::MonitorWifiLock")
            } else {
                @Suppress("DEPRECATION") // Owner-approved legacy branch: pre-Q has no tag-only WifiLock API.
                wifiManager.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "EntryRecorder::MonitorWifiLock"
                )
            }.apply {
                acquire()
            }
        } catch (e: Exception) {
            Log.w(tag, "Could not acquire Wake/WiFi locks", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(tag, "IntercomMonitorService onDestroy")
        serviceScope.cancel()

        for (device in activeDevices.values) {
            runBlocking {
                device.stopMonitoring()
            }
        }
        activeDevices.clear()

        for (analyzer in activeMotionAnalyzers.values) {
            analyzer.stop()
        }
        activeMotionAnalyzers.clear()
        MonitorStatusHolder.clear()

        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}

        pendingPostRecordStops.clear()
        lastRingHandledAt.clear()
        sipManager.onIncomingCall = null

        sipManager.destroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        /** Action used by the persistent foreground notification to stop monitoring in place. */
        const val ACTION_STOP = "io.github.mvolkert.entryrecorder.service.ACTION_STOP"

        // Rings from one doorbell press reach the app from up to three sources within a second; anything
        // arriving inside this window after a handled ring is treated as the same press. Note that this also
        // swallows a genuine second press inside the window (the recording keeps running, no second alert).
        private const val RING_DEBOUNCE_MS = 5000L

        // Trigger recordings run to the post-record buffer plus this headroom: the *Ended event is what
        // normally stops them, so the timer only has to outlive a missing end event.
        private const val TRIGGER_RECORD_HEADROOM_SECONDS = 30

        fun start(context: Context) {
            val intent = Intent(context, IntercomMonitorService::class.java)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                // Android 12+/14 may reject background FGS starts (e.g. from BOOT) with
                // ForegroundServiceStartNotAllowedException. Log instead of crashing the receiver.
                Log.e("IntercomMonitorService", "Foreground service start not allowed", e)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, IntercomMonitorService::class.java)
            context.stopService(intent)
        }
    }
}
