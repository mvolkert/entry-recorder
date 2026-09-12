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

    override fun onCreate() {
        super.onCreate()

        // 1. Setup notification channels
        NotificationHelper.createNotificationChannels(this)

        // 2. Schedule daily retention cleanup worker
        scheduleRetentionCleanup()

        // 3. Start background monitoring service
        IntercomMonitorService.start(this)
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
}
