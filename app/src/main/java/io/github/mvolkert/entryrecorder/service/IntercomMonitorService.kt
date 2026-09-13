package io.github.mvolkert.entryrecorder.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import io.github.mvolkert.entryrecorder.EntryRecorderApp
import io.github.mvolkert.entryrecorder.data.device.IntercomDeviceFactory
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.domain.device.IntercomDevice
import io.github.mvolkert.entryrecorder.domain.device.IntercomEvent
import io.github.mvolkert.entryrecorder.domain.device.IntercomEventListener
import io.github.mvolkert.entryrecorder.notification.NotificationHelper
import io.github.mvolkert.entryrecorder.ui.incoming.IncomingCallActivity
import io.github.mvolkert.entryrecorder.video.OnDeviceMotionAnalyzer
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

class IntercomMonitorService : Service(), IntercomEventListener {

    private val tag = "IntercomMonitorService"
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val activeDevices = ConcurrentHashMap<Long, IntercomDevice>()
    private val activeMotionAnalyzers = ConcurrentHashMap<Long, OnDeviceMotionAnalyzer>()
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
        startForeground(
            NotificationHelper.NOTIFICATION_ID_SERVICE,
            NotificationHelper.buildServiceNotification(this, 0)
        )

        // Initialize SIP engine
        sipManager.initialize()

        // Observe devices from database and update monitoring
        serviceScope.launch {
            repository.allDevices.collect { devices ->
                updateMonitoredDevices(devices.filter { it.isEnabled })
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(tag, "IntercomMonitorService onStartCommand (action=${intent?.action})")
        return START_STICKY
    }

    private suspend fun updateMonitoredDevices(enabledDevices: List<DeviceEntity>) = withContext(Dispatchers.IO) {
        val currentIds = activeDevices.keys.toSet()
        val newIds = enabledDevices.map { it.id }.toSet()

        // Remove removed/disabled devices
        for (id in currentIds - newIds) {
            val device = activeDevices.remove(id)
            device?.stopMonitoring()
        }

        // Add or update active devices
        for (entity in enabledDevices) {
            val existing = activeDevices[entity.id]
            if (existing == null || existing.deviceEntity != entity) {
                existing?.stopMonitoring()
                val newDevice = IntercomDeviceFactory.createDevice(entity)
                activeDevices[entity.id] = newDevice
                newDevice.startMonitoring(this@IntercomMonitorService)

                // Configure SIP for device
                sipManager.configureDeviceSip(entity)
            }

            // Manage the on-device (app-side) motion analyzer independently of the device implementation
            val existingAnalyzer = activeMotionAnalyzers[entity.id]
            if (entity.recordOnMotionOnDevice) {
                if (existingAnalyzer == null) {
                    val analyzer = OnDeviceMotionAnalyzer(entity, this@IntercomMonitorService)
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
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
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

                    // 1. Start Recording if configured
                    if (device.recordOnRing) {
                        recorder.startRecording(
                            device = device,
                            eventType = EventType.RING,
                            maxDurationSeconds = device.ringRecordSeconds
                        )
                    }

                    // 2. Wake lockscreen & notify
                    NotificationHelper.showDoorbellNotification(this@IntercomMonitorService, device, event.callerNumber)

                    // 3. Launch IncomingCallActivity directly for immediate lockscreen display
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

                is IntercomEvent.MotionStarted -> {
                    val device = event.device
                    Log.i(tag, "Motion started on ${device.name}")

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
                    // Allow post-record time buffer then stop
                    serviceScope.launch {
                        delay(device.motionPostRecordSeconds * 1000L)
                        recorder.stopRecording(device.id)
                    }
                }

                is IntercomEvent.NoiseStarted -> {
                    val device = event.device
                    Log.i(tag, "Noise started on ${device.name}")

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
                    serviceScope.launch {
                        delay(device.noisePostRecordSeconds * 1000L)
                        recorder.stopRecording(device.id)
                    }
                }

                is IntercomEvent.MotionOnDeviceStarted -> {
                    val device = event.device
                    Log.i(tag, "On-device motion analysis started on ${device.name}")

                    if (device.recordOnMotionOnDevice) {
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

                is IntercomEvent.MotionOnDeviceEnded -> {
                    val device = event.device
                    Log.i(tag, "On-device motion analysis ended on ${device.name}")
                    serviceScope.launch {
                        delay(device.motionPostRecordSeconds * 1000L)
                        recorder.stopRecording(device.id)
                    }
                }

                is IntercomEvent.CallState -> {
                    Log.d(tag, "Intercom call state: ${event.state} for ${event.device.name}")
                }

                is IntercomEvent.ConnectionState -> {
                    Log.d(tag, "Device ${event.device.name} connection: ${event.isConnected} (${event.message})")
                }

                is IntercomEvent.Error -> {
                    Log.e(tag, "Device ${event.device.name} error: ${event.error.message}")
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireWakeAndWifiLocks() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "EntryRecorder::MonitorWakeLock"
            ).apply {
                acquire(24 * 60 * 60 * 1000L) // 24h
            }

            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "EntryRecorder::MonitorWifiLock"
            ).apply {
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

        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}

        sipManager.destroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, IntercomMonitorService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, IntercomMonitorService::class.java)
            context.stopService(intent)
        }
    }
}
