package io.github.mvolkert.entryrecorder

import android.app.Application
import timber.log.Timber
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Constraints
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.HiltAndroidApp
import io.github.mvolkert.entryrecorder.data.repository.IntercomRepository
import io.github.mvolkert.entryrecorder.di.ApplicationScope
import io.github.mvolkert.entryrecorder.notification.NotificationHelper
import io.github.mvolkert.entryrecorder.service.IntercomMonitorService
import io.github.mvolkert.entryrecorder.util.ExportFailureMonitor
import io.github.mvolkert.entryrecorder.util.FileLoggingPlanter
import io.github.mvolkert.entryrecorder.worker.RetentionCleanupWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Application entry point. Everything the app used to hand-construct here — the database, the repository,
 * the recorder, the SIP manager — now comes from the Hilt graph in `di/AppModule.kt`; what is left is the
 * work only an Application can do: process-wide setup and the observer that keeps the monitor service
 * aligned with the enabled-camera set.
 */
@HiltAndroidApp
class EntryRecorderApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var repository: IntercomRepository

    // Retained export-write failure reported by the background write paths; surfaced as a notification
    // here because nothing else runs for the whole process while the app is backgrounded.
    @Inject
    lateinit var exportFailureMonitor: ExportFailureMonitor

    // Lives for the whole process; used only to react to the enabled-camera set and start/stop the
    // monitoring service. Cancelling it would stop the service, so it is deliberately never cancelled.
    @Inject
    @ApplicationScope
    lateinit var appScope: CoroutineScope

    /**
     * WorkManager is initialised on demand (its own startup initializer is removed in the manifest), so
     * this is what gives it a factory able to construct the `@HiltWorker` classes.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()

        // Logcat tree, always: the app-wide facade every class now logs through. Planted first so the setup
        // below is itself captured. The file tree is planted later, only while the Settings toggle is on.
        Timber.plant(Timber.DebugTree())

        // 0. Setup crash protection for known Media3 RTSP bugs
        setupExoPlayerCrashProtection()

        // 1. Setup notification channels
        NotificationHelper.createNotificationChannels(this)

        // 2. Schedule daily retention cleanup worker
        scheduleRetentionCleanup()

        // 3. Keep the monitoring foreground service aligned with the enabled-camera set. A running
        //    foreground service must show a persistent notification, so rather than display
        //    "Monitoring 0 devices" when nothing is watched, the service runs only while at least one
        //    camera is enabled. This observer only ever *starts* the service; disabling the last camera
        //    is handled by the service stopping itself, because calling stopService() here could cancel a
        //    pending startForegroundService() before its onCreate reaches startForeground().
        observeMonitorDemand()

        // 4. Surface a retained export-write failure as a persistent notification while the app is
        //    backgrounded, and withdraw it once a write lands again.
        observeExportFailures()

        // 5. Plant or uproot the file-capture tree as the Settings toggle changes, so turning logging to
        //    file on or off takes effect at runtime without a restart.
        observeFileLogging()
    }

    private fun observeFileLogging() {
        // The plant/uproot decision itself is a tested unit; this only feeds it the persisted toggle.
        val planter = FileLoggingPlanter.forFilesDir(filesDir)
        appScope.launch {
            repository.settingsFlow.collect { settings ->
                planter.setFileLoggingEnabled(settings?.loggingToFileEnabled == true)
            }
        }
    }

    private fun observeMonitorDemand() {
        appScope.launch {
            repository.allDevices.collect { devices ->
                if (devices.any { it.isEnabled }) {
                    IntercomMonitorService.start(this@EntryRecorderApp)
                }
            }
        }
    }

    private fun observeExportFailures() {
        appScope.launch {
            exportFailureMonitor.failure.collect { failure ->
                if (failure == null) {
                    NotificationHelper.cancelExportFailureNotice(this@EntryRecorderApp)
                } else {
                    NotificationHelper.showExportFailureNotice(this@EntryRecorderApp)
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
                Timber.tag("EntryRecorderApp").e(throwable, "Caught fatal Media3 RTSP crash on thread ${thread.name}. Preventing app exit.")
                // We don't call the default handler, so the process stays alive.
                // The player UI will eventually show an error or the user will retry.
            } else {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }
}
