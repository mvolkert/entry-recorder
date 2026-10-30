package io.github.mvolkert.entryrecorder.push

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mvolkert.entryrecorder.domain.alert.RemoteMotionAlert
import io.github.mvolkert.entryrecorder.domain.alert.RemoteMotionAlertPresenter
import io.github.mvolkert.entryrecorder.notification.NotificationHelper
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts a relayed motion alert as a heads-up notification (change `add-motion-notification-to-other-phone`).
 *
 * The presenter exists as an interface so [RemoteMotionAlertHandler] can be tested on the JVM: everything up to
 * the decision to notify is pure, and only this one class touches the framework.
 */
@Singleton
class RemoteMotionAlertNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : RemoteMotionAlertPresenter {

    override fun show(alert: RemoteMotionAlert) {
        NotificationHelper.showRemoteMotionAlert(context, alert)
    }
}
