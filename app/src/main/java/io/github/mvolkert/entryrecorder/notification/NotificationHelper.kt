package io.github.mvolkert.entryrecorder.notification

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.service.IntercomMonitorService
import io.github.mvolkert.entryrecorder.ui.MainActivity
import io.github.mvolkert.entryrecorder.ui.incoming.IncomingCallActivity

object NotificationHelper {

    const val CHANNEL_SERVICE = "entry_recorder_service"
    const val CHANNEL_DOORBELL = "entry_recorder_doorbell"
    const val CHANNEL_MOTION = "entry_recorder_motion"
    const val CHANNEL_NOISE = "entry_recorder_noise"

    const val NOTIFICATION_ID_SERVICE = 1001

    // Non-overlapping id space per (event type, device): base + device*4 + slot.
    // Prevents cross-channel collisions for different devices (the previous 1002/1003/1004 + id
    // scheme let e.g. doorbell id=2 == motion id=1) and bounds the value to avoid Long.toInt() wrap.
    private const val FIRST_EVENT_NOTIFICATION_ID = 10000
    private const val SLOT_DOORBELL = 1
    private const val SLOT_MOTION = 2
    private const val SLOT_NOISE = 3

    private fun eventNotificationId(slot: Int, deviceId: Long): Int =
        FIRST_EVENT_NOTIFICATION_ID + (deviceId % 1000L * 4L + slot).toInt()

    fun createNotificationChannels(context: Context) {
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

        // Noise Alert Channel
        val noiseChannel = NotificationChannel(
            CHANNEL_NOISE,
            context.getString(R.string.channel_noise_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.channel_noise_desc)
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        manager.createNotificationChannels(listOf(serviceChannel, doorbellChannel, motionChannel, noiseChannel))
    }

    fun buildServiceNotification(context: Context, activeDevicesCount: Int): Notification {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Stop/Exit action: a getService() PendingIntent delivers ACTION_STOP to onStartCommand,
        // which calls stopSelf(). This gives the user a way to halt the 24/7 monitor from the shade.
        val stopIntent = Intent(context, IntercomMonitorService::class.java).apply {
            action = IntercomMonitorService.ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            context, NOTIFICATION_ID_SERVICE, stopIntent,
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
            .addAction(android.R.drawable.ic_media_pause, "Stop monitoring", stopPendingIntent)
            .build()
    }

    @SuppressLint("FullScreenIntentPolicy")
    fun showDoorbellNotification(
        context: Context,
        device: DeviceEntity,
        caller: String?,
        playSound: Boolean = true,
        vibrate: Boolean = true
    ) {
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
            eventNotificationId(SLOT_DOORBELL, device.id),
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
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .setAutoCancel(true)

        if (playSound) notification.setSound(ringUri) else notification.setSilent(true)
        if (vibrate) notification.setVibrate(longArrayOf(0, 500, 200, 500, 200, 800))

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(eventNotificationId(SLOT_DOORBELL, device.id), notification.build())
    }

    fun showMotionNotification(context: Context, device: DeviceEntity) {
        val intent = Intent(context, IncomingCallActivity::class.java).apply {
            putExtra(IncomingCallActivity.EXTRA_DEVICE_ID, device.id)
            putExtra(IncomingCallActivity.EXTRA_EVENT_TYPE, EventType.MOTION.name)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            eventNotificationId(SLOT_MOTION, device.id),
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
        manager.notify(eventNotificationId(SLOT_MOTION, device.id), notification)
    }

    fun showNoiseNotification(context: Context, device: DeviceEntity) {
        val intent = Intent(context, IncomingCallActivity::class.java).apply {
            putExtra(IncomingCallActivity.EXTRA_DEVICE_ID, device.id)
            putExtra(IncomingCallActivity.EXTRA_EVENT_TYPE, EventType.NOISE.name)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            eventNotificationId(SLOT_NOISE, device.id),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_NOISE)
            .setContentTitle("Noise Detected: ${device.name}")
            .setContentText("Sound registered at ${device.ipAddress}")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(eventNotificationId(SLOT_NOISE, device.id), notification)
    }
}
