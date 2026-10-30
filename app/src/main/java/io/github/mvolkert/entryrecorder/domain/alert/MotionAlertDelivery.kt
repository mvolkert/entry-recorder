package io.github.mvolkert.entryrecorder.domain.alert

import io.github.mvolkert.entryrecorder.util.redactBearerUrl

/**
 * The one wording for a motion alert the monitoring phone could not hand to the server (change
 * `add-motion-notification-to-other-phone`).
 *
 * Deliberately not a [io.github.mvolkert.entryrecorder.util.ConnectionLog] line: whether the Python server is
 * reachable is decided by the transport's shared link clock, which counts a transport failure only. The server
 * answering 400 (an address it refuses to dial) or 502 (the recipient's push distributor rejected the alert) is
 * a live server that did not deliver, so the caller reports the delivery and stays out of the outage timeline.
 *
 * The recipient is a bearer address whose path carries the token that lets its holder push to that phone, so
 * only its origin appears — the same rule the transport's own log line follows.
 */
object MotionAlertDelivery {

    /** One WARN line naming the camera, the recipient's origin and why the relay did not succeed. */
    fun failure(deviceName: String, recipient: String, cause: Throwable): String =
        "Motion alert for $deviceName was not delivered to ${redactBearerUrl(recipient)} " +
            "(${cause.message ?: cause.javaClass.simpleName})"
}
