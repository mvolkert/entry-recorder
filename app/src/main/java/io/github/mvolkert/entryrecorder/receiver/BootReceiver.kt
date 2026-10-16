package io.github.mvolkert.entryrecorder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.mvolkert.entryrecorder.data.local.prefs.AutoBootPrefs
import io.github.mvolkert.entryrecorder.service.IntercomMonitorService

class BootReceiver : BroadcastReceiver {
    constructor() : super()

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i("BootReceiver", "Received boot/package intent: $action")
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // The monitor autostart is user-controlled now; read the synchronous mirror rather than open
            // Room on this broadcast thread. Default-on keeps an upgraded install's prior behaviour.
            if (!AutoBootPrefs.isEnabled(context)) {
                Log.i("BootReceiver", "Auto Boot disabled; not starting the monitor service")
                return
            }
            IntercomMonitorService.start(context)
        }
    }
}
