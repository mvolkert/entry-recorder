package io.github.mvolkert.entryrecorder.push

import android.app.Activity
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.unifiedpush.android.connector.UnifiedPush
import javax.inject.Inject

/**
 * The receiver side of motion alerts (change `add-motion-notification-to-other-phone`): asks a UnifiedPush
 * distributor to register this install, and drops the registration again when the user opts out.
 *
 * Kept behind an interface so the settings ViewModel drives the opt-in from a unit test without the connector,
 * a distributor app or a Context, exactly as [io.github.mvolkert.entryrecorder.domain.alert.MotionAlertTransport]
 * keeps the sender side fakeable.
 */
interface MotionAlertRegistrar {

    /** Whether any distributor app is installed that this phone could register with. */
    fun hasDistributor(): Boolean

    /**
     * Registers with the distributor already chosen for this install. When there is none, [activity] is needed to
     * hand the choice to the system's `unifiedpush://link` picker; without one the call reports `false` rather
     * than starting an activity from a non-activity context. [onResult] says whether a distributor is in place —
     * the endpoint itself arrives later, through [MotionAlertPushService].
     */
    fun register(activity: Activity?, onResult: (Boolean) -> Unit)

    /** Unsubscribes, so a phone the user opted out stops being pushed at. */
    fun unregister()
}

/** The connector-backed implementation of [MotionAlertRegistrar]. */
class UnifiedPushMotionAlertRegistrar @Inject constructor(
    @ApplicationContext private val context: Context,
) : MotionAlertRegistrar {

    override fun hasDistributor(): Boolean = UnifiedPush.getDistributors(context).isNotEmpty()

    override fun register(activity: Activity?, onResult: (Boolean) -> Unit) {
        if (UnifiedPush.getAckDistributor(context) != null) {
            UnifiedPush.register(context)
            onResult(true)
            return
        }
        val picker = activity ?: run {
            onResult(false)
            return
        }
        UnifiedPush.tryUseCurrentOrDefaultDistributor(picker) { chosen ->
            if (chosen) UnifiedPush.register(context)
            onResult(chosen)
        }
    }

    override fun unregister() = UnifiedPush.unregister(context)
}
