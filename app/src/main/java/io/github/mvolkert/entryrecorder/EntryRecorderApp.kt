package io.github.mvolkert.entryrecorder

import android.app.Application
import androidx.work.*
import io.github.mvolkert.entryrecorder.data.local.AppDatabase
import io.github.mvolkert.entryrecorder.data.repository.IntercomRepository
import io.github.mvolkert.entryrecorder.notification.NotificationHelper
import io.github.mvolkert.entryrecorder.service.IntercomMonitorService
import io.github.mvolkert.entryrecorder.sip.SipCallManager
import io.github.mvolkert.entryrecorder.video.RtspStreamRecorder
import io.github.mvolkert.entryrecorder.worker.RetentionCleanupWorker
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class EntryRecorderApp : Application() {

    val database by lazy { AppDatabase.getDatabase(this) }
    val repository by lazy {
        IntercomRepository(
            deviceDao = database.deviceDao(),
            recordingDao = database.recordingDao(),
            appSettingsDao = database.appSettingsDao()
        )
    }
    val recorder by lazy { RtspStreamRecorder(this, repository) }
    val sipCallManager by lazy { SipCallManager.getInstance(this) }

    // Lives for the whole process; used only to react to the enabled-camera set and start/stop the
    // monitoring service. Cancelling it would stop the service, so it is deliberately never cancelled.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        // 0. Setup crash protection for known Media3 RTSP bugs
        setupExoPlayerCrashProtection()

        // 1. Setup notification channels
        NotificationHelper.createNotificationChannels(this)

        // 2. Schedule daily retention cleanup worker
        scheduleRetentionCleanup()

        // 3. Keep the monitoring foreground service aligned with the enabled-camera set. A running
        //    foreground service must show a persistent notification, so rather than display
        //    "Monitoring 0 devices" when nothing is watched, the service runs only while at least one
        //    camera is enabled: enabling a camera starts it, disabling the last one stops it.
        observeMonitorDemand()
    }

    private fun observeMonitorDemand() {
        appScope.launch {
            repository.allDevices.collect { devices ->
                if (devices.any { it.isEnabled }) {
                    IntercomMonitorService.start(this@EntryRecorderApp)
                } else {
                    IntercomMonitorService.stop(this@EntryRecorderApp)
                }
            }
        }
    }

    private fun scheduleRetentionCleanup() {
        val cleanupRequest = PeriodicWorkRequestBuilder<RetentionCleanupWorker>(
            repeatInterval = 1,
            repeatIntervalTimeUnit = TimeUnit.DAYS
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "RetentionCleanupWork",
            ExistingPeriodicWorkPolicy.KEEP,
            cleanupRequest
        )
    }

    // NOTE: Targeted workaround for a known Media3 RTSP defect that throws a hard
    // NullPointerException on the playback thread. That crash is NOT surfaced as a normal
    // PlaybackException (players already handle onPlayerError), so this narrow global guard is the
    // only way to keep the process alive on affected devices. It is deliberately scoped to the
    // exact ExoPlayer-RTSP-NPE signature and forwards every other throwable to the default handler.
    private fun setupExoPlayerCrashProtection() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Specifically catch the Media3 RTSP NullPointerException to prevent fatal app exit
            val isExoPlayerRtspCrash = thread.name.contains("ExoPlayer:Playback") &&
                    throwable is NullPointerException &&
                    throwable.stackTrace.any { it.className.contains("RtspClient") }

            if (isExoPlayerRtspCrash) {
                Log.e("EntryRecorderApp", "Caught fatal Media3 RTSP crash on thread ${thread.name}. Preventing app exit.", throwable)
                // We don't call the default handler, so the process stays alive.
                // The player UI will eventually show an error or the user will retry.
            } else {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }
}
