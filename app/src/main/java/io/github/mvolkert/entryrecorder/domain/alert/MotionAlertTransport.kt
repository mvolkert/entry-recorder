package io.github.mvolkert.entryrecorder.domain.alert

/**
 * A motion event to relay to one remote phone. Carries everything a backend needs to deliver it — the server
 * coordinates to relay through, the recipient's push endpoint to reach, and what happened — so the transport
 * stays a thin, fakeable seam with no dependency on the monitor service or Room. [recipient] is a bearer
 * address: it is never logged in clear and never leaves this phone except as the POST body to the server.
 */
data class MotionAlert(
    val serverBaseUrl: String,
    val apiKey: String,
    val recipient: String,
    val deviceName: String,
    val timestampMs: Long,
)

/**
 * Seam for delivering a detected-motion alert to a remote phone (change `add-motion-notification-to-other-phone`).
 *
 * The owner elected (2026-10-16) an ordered delivery ladder — relay through the authenticated Python server
 * first (already the event-transport backend, works behind NAT), then vendor-free UnifiedPush, then FCM as a
 * fallback. The server-relay rung is what this interface has one implementation of, and it stayed the answer
 * once UnifiedPush was approved (2026-10-30): the *recipient* runs the connector, so the sender still posts to
 * the server and the server performs the final hop. FCM stays an Ask-First rung.
 *
 * Keeping this a seam is what lets the monitor service stay free of any delivery mechanism; the recipient's
 * address is a bearer URL, so a failure of [send] is a delivery outcome and never a statement about the
 * network — see [io.github.mvolkert.entryrecorder.data.alert.ServerRelayMotionAlertTransport].
 */
interface MotionAlertTransport {
    /** Delivers [alert]; a failure is the transport error, never a thrown exception (cancellation aside). */
    suspend fun send(alert: MotionAlert): Result<Unit>
}
