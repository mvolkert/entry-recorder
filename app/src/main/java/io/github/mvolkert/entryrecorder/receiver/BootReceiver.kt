package io.github.mvolkert.entryrecorder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import timber.log.Timber
import io.github.mvolkert.entryrecorder.data.local.prefs.AutoBootPrefs
import io.github.mvolkert.entryrecorder.data.local.prefs.MotionAlertPushPrefs
import io.github.mvolkert.entryrecorder.push.MotionAlertRegistrar
import io.github.mvolkert.entryrecorder.service.IntercomMonitorService

class BootReceiver : BroadcastReceiver {
    constructor() : super()

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Timber.tag("BootReceiver").i("Received boot/package intent: $action")
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // First, and independently of the monitor autostart below (which returns early when it is off):
            // a push registration belongs to the install, so an opted-in phone re-attaches to its distributor
            // after a reboot or an app update instead of sitting out alerts until the user opens the app.
            reRegisterMotionAlerts(context)

            // The monitor autostart is user-controlled now; read the synchronous mirror rather than open
            // Room on this broadcast thread. Default-on keeps an upgraded install's prior behaviour.
            if (!AutoBootPrefs.isEnabled(context)) {
                Timber.tag("BootReceiver").i("Auto Boot disabled; not starting the monitor service")
                return
            }
            IntercomMonitorService.start(context)
        }
    }

    /**
     * Re-registers the recipient when the user turned on "Receive motion alerts on this phone"
     * (change `add-motion-notification-to-other-phone`). No Activity exists at boot, so a phone whose
     * distributor choice was lost is reported rather than prompted — the settings screen offers the picker.
     */
    private fun reRegisterMotionAlerts(context: Context) {
        if (!MotionAlertPushPrefs.isReceiveEnabled(context)) return
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            BootEntryPoint::class.java,
        )
        entryPoint.motionAlertRegistrar().register(activity = null) { distributorChosen ->
            Timber.tag("BootReceiver").i(
                if (distributorChosen) "Re-registered for motion alerts"
                else "Motion alerts are opted in but no distributor is selected; the settings screen must pick one"
            )
        }
    }
}

/** The one seam the boot receiver needs from the graph; it is not a Hilt-managed component itself. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface BootEntryPoint {
    fun motionAlertRegistrar(): MotionAlertRegistrar
}
