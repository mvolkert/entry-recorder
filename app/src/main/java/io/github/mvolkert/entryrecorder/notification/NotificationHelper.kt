package io.github.mvolkert.entryrecorder.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.ui.MainActivity
import io.github.mvolkert.entryrecorder.ui.incoming.IncomingCallActivity

object NotificationHelper {

    const val CHANNEL_SERVICE = "entry_recorder_service"
    const val CHANNEL_DOORBELL = "entry_recorder_doorbell"
    const val CHANNEL_MOTION = "entry_recorder_motion"

    const val NOTIFICATION_ID_SERVICE = 1001
    const val NOTIFICATION_ID_DOORBELL = 1002
    const val NOTIFICATION_ID_MOTION = 1003

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Background Service Channel
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                context.getString(R.string.channel_service_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.channel_service_desc)
                setShowBadge(false)
            }

            // Doorbell Ring / Incoming Call Channel (Max importance for full-screen lockscreen alert)
            val ringUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .build()

            val doorbellChannel = NotificationChannel(
                CHANNEL_DOORBELL,
                context.getString(R.string.channel_call_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.channel_call_desc)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 800)
                setSound(ringUri, audioAttributes)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            }

            // Motion Alert Channel
            val motionChannel = NotificationChannel(
                CHANNEL_MOTION,
                context.getString(R.string.channel_motion_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.channel_motion_desc)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            manager.createNotificationChannels(listOf(serviceChannel, doorbellChannel, motionChannel))
        }
    }

    fun buildServiceNotification(context: Context, activeDevicesCount: Int): Notification {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setContentTitle("EntryRecorder Active")
            .setContentText("Monitoring $activeDevicesCount intercom device(s) on LAN")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    fun showDoorbellNotification(context: Context, device: DeviceEntity, caller: String?) {
        val fullScreenIntent = Intent(context, IncomingCallActivity::class.java).apply {
            putExtra(IncomingCallActivity.EXTRA_DEVICE_ID, device.id)
            putExtra(IncomingCallActivity.EXTRA_EVENT_TYPE, EventType.RING.name)
            putExtra(IncomingCallActivity.EXTRA_CALLER, caller ?: "Doorbell")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            device.id.toInt(),
            fullScreenIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val ringUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        val notification = NotificationCompat.Builder(context, CHANNEL_DOORBELL)
            .setContentTitle("Doorbell Ringing: ${device.name}")
            .setContentText("Incoming ring from ${caller ?: device.ipAddress}")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSound(ringUri)
            .setVibrate(longArrayOf(0, 500, 200, 500, 200, 800))
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID_DOORBELL + device.id.toInt(), notification)
    }

    fun showMotionNotification(context: Context, device: DeviceEntity) {
        val intent = Intent(context, IncomingCallActivity::class.java).apply {
            putExtra(IncomingCallActivity.EXTRA_DEVICE_ID, device.id)
            putExtra(IncomingCallActivity.EXTRA_EVENT_TYPE, EventType.MOTION.name)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            (device.id + 1000).toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_MOTION)
            .setContentTitle("Motion Detected: ${device.name}")
            .setContentText("Movement registered at ${device.ipAddress}")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID_MOTION + device.id.toInt(), notification)
    }
}
