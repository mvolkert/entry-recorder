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
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.data.model.MonitorStatus
import io.github.mvolkert.entryrecorder.domain.device.IntercomDevice
import io.github.mvolkert.entryrecorder.domain.device.IntercomEvent
import io.github.mvolkert.entryrecorder.domain.device.IntercomEventListener
import io.github.mvolkert.entryrecorder.notification.NotificationHelper
import io.github.mvolkert.entryrecorder.ui.incoming.IncomingCallActivity
import io.github.mvolkert.entryrecorder.video.OnDeviceMotionAnalyzer
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
        val serviceNotification = NotificationHelper.buildServiceNotification(this, 0)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
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
            val settings = repository.getSettings()

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
                        playSound = settings.soundOnRing,
                        vibrate = settings.vibrateOnRing
                    )

                    // 3. Launch IncomingCallActivity directly for immediate lockscreen display.
                    // NOTE (targetSdk 36+): background Activity Launch is restricted, so this
                    // direct startActivity is best-effort. The doorbell path stays reliable because
                    // NotificationHelper attaches a full-screen intent to the ring notification.
                    if (settings.wakeOnRing) {
                        val callIntent = Intent(this@IntercomMonitorService, IncomingCallActivity::class.java).apply {
                            putExtra(IncomingCallActivity.EXTRA_DEVICE_ID, device.id)
                            putExtra(IncomingCallActivity.EXTRA_EVENT_TYPE, EventType.RING.name)
                            putExtra(IncomingCallActivity.EXTRA_CALLER, event.callerNumber ?: "2N IP Verso")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        startActivity(callIntent)
                    }
                }

                is IntercomEvent.MotionStarted -> {
                    val device = event.device
                    Log.i(tag, "Motion started on ${device.name}")
                    MonitorStatusHolder.update(device.id, MonitorStatus.MOTION)

                    cancelPostRecordStop(device.id)
                    if (device.recordOnMotion) {
                        recorder.startRecording(
                            device = device,
                            eventType = EventType.MOTION,
                            maxDurationSeconds = device.motionPostRecordSeconds + 30
                        )
                    }

                    if (settings.wakeOnMotion) {
                        NotificationHelper.showMotionNotification(this@IntercomMonitorService, device)
                        val motionIntent = Intent(this@IntercomMonitorService, IncomingCallActivity::class.java).apply {
                            putExtra(IncomingCallActivity.EXTRA_DEVICE_ID, device.id)
                            putExtra(IncomingCallActivity.EXTRA_EVENT_TYPE, EventType.MOTION.name)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        startActivity(motionIntent)
                    }
                }

                is IntercomEvent.MotionEnded -> {
                    val device = event.device
                    Log.i(tag, "Motion ended on ${device.name}")
                    MonitorStatusHolder.update(device.id, MonitorStatus.MONITORING)
                    // Allow post-record time buffer then stop
                    schedulePostRecordStop(device, EventType.MOTION, device.motionPostRecordSeconds)
                }

                is IntercomEvent.NoiseStarted -> {
                    val device = event.device
                    Log.i(tag, "Noise started on ${device.name}")

                    cancelPostRecordStop(device.id)
                    if (device.recordOnNoise) {
                        recorder.startRecording(
                            device = device,
                            eventType = EventType.NOISE,
                            maxDurationSeconds = device.noisePostRecordSeconds + 30
                        )
                    }

                    if (settings.wakeOnNoise) {
                        NotificationHelper.showNoiseNotification(this@IntercomMonitorService, device)
                        val noiseIntent = Intent(this@IntercomMonitorService, IncomingCallActivity::class.java).apply {
                            putExtra(IncomingCallActivity.EXTRA_DEVICE_ID, device.id)
                            putExtra(IncomingCallActivity.EXTRA_EVENT_TYPE, EventType.NOISE.name)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        startActivity(noiseIntent)
                    }
                }

                is IntercomEvent.NoiseEnded -> {
                    val device = event.device
                    Log.i(tag, "Noise ended on ${device.name}")
                    // Allow post-record time buffer then stop
                    schedulePostRecordStop(device, EventType.NOISE, device.noisePostRecordSeconds)
                }

                is IntercomEvent.MotionOnDeviceStarted -> {
                    val device = event.device
                    Log.i(tag, "On-device motion analysis started on ${device.name}")
                    MonitorStatusHolder.update(device.id, MonitorStatus.MOTION)

                    cancelPostRecordStop(device.id)
                    if (device.recordOnMotionOnDevice) {
                        // Prepend the frames the analyzer saw just before it confirmed motion, so the
                        // recording covers the arrival rather than starting ~1s after it.
                        val preRoll = activeMotionAnalyzers[device.id]?.drainPreRoll().orEmpty()
                        recorder.startRecording(
                            device = device,
                            eventType = EventType.MOTION,
                            maxDurationSeconds = device.motionPostRecordSeconds + 30,
                            preRoll = preRoll
                        )
                    }

                    if (settings.wakeOnMotion) {
                        NotificationHelper.showMotionNotification(this@IntercomMonitorService, device)
                        val motionIntent = Intent(this@IntercomMonitorService, IncomingCallActivity::class.java).apply {
                            putExtra(IncomingCallActivity.EXTRA_DEVICE_ID, device.id)
                            putExtra(IncomingCallActivity.EXTRA_EVENT_TYPE, EventType.MOTION.name)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        startActivity(motionIntent)
                    }
                }

                is IntercomEvent.MotionOnDeviceEnded -> {
                    val device = event.device
                    Log.i(tag, "On-device motion analysis ended on ${device.name}")
                    MonitorStatusHolder.update(device.id, MonitorStatus.MONITORING)
                    schedulePostRecordStop(device, EventType.MOTION, device.motionPostRecordSeconds)
                }

                is IntercomEvent.CallState -> {
                    Log.d(tag, "Intercom call state: ${event.state} for ${event.device.name}")
                }

                is IntercomEvent.ConnectionState -> {
                    val detail = "Device ${event.device.name} connection: ${event.isConnected} (${event.message})"
                    // Degraded monitoring (e.g. the 2N polling fallback that cannot see doorbells at all) has
                    // to be visible in logcat and crash telemetry, not buried at debug level.
                    if (event.isConnected) Log.i(tag, detail) else Log.w(tag, detail)
                }

                is IntercomEvent.Error -> {
                    Log.e(tag, "Device ${event.device.name} error: ${event.error.message}")
                }
            }
        }
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
        // arriving inside this window after a handled ring is treated as the same press.
        private const val RING_DEBOUNCE_MS = 5000L

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
