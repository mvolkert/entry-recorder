package io.github.mvolkert.entryrecorder.ui.incoming

import android.content.Context
import android.content.Intent
import timber.log.Timber
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.domain.call.CallScreenLauncher
import io.github.mvolkert.entryrecorder.domain.model.Device
import javax.inject.Inject

/**
 * The UI implementation of [CallScreenLauncher]: it builds the [IncomingCallActivity] intent and starts it.
 * Living here keeps the Android activity mechanics in the presentation layer, so the monitor service reaches
 * the call screen through the [CallScreenLauncher] port instead of importing `ui` directly.
 *
 * NOTE (targetSdk 36+): background activity launch is restricted, so this direct start is best-effort. When
 * a device opts into wake-on-ring the full-screen intent attached to its notification is the reliable path to
 * the lockscreen; this call is the opportunistic fast path.
 */
class IncomingCallLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
) : CallScreenLauncher {

    override fun launch(device: Device, eventType: EventType, caller: String?) {
        val intent = Intent(context, IncomingCallActivity::class.java).apply {
            putExtra(IncomingCallActivity.EXTRA_DEVICE_ID, device.id)
            putExtra(IncomingCallActivity.EXTRA_EVENT_TYPE, eventType.name)
            caller?.let { putExtra(IncomingCallActivity.EXTRA_CALLER, it) }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // Background activity launch can be rejected by the platform; the notification path still stands,
            // but a silently dropped call screen must be visible in logcat.
            Timber.tag(TAG).w(e, "Could not launch the call screen for ${device.name}")
        }
    }

    private companion object {
        const val TAG = "IncomingCallLauncher"
    }
}
