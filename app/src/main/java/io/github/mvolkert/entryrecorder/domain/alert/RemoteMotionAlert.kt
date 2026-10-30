package io.github.mvolkert.entryrecorder.domain.alert

/**
 * A motion event that another phone detected and the Python server relayed to this device (change
 * `add-motion-notification-to-other-phone`).
 *
 * It carries the camera's name in words rather than an id: the receiving phone has no local row for a device
 * the sender owns, so a name is the only thing it can show or tap through.
 */
data class RemoteMotionAlert(
    val deviceName: String,
    val monitorName: String,
    val timestampMs: Long,
)

/**
 * The presentation seam for a relayed alert, kept apart from the parsing so the decision about *what* to show
 * is testable without a [android.content.Context] or a [android.app.NotificationManager].
 */
interface RemoteMotionAlertPresenter {

    /** Shows one alert. */
    fun show(alert: RemoteMotionAlert)
}
