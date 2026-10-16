package io.github.mvolkert.entryrecorder.domain.alert

/**
 * A motion event to relay to one remote phone. Carries everything a backend needs to deliver it — the server
 * coordinates to relay through, the recipient to reach, and what happened — so the transport stays a thin,
 * fakeable seam with no dependency on the monitor service or Room.
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
 * fallback. UnifiedPush and FCM are Ask-First dependencies, so today only the server-relay backend exists; the
 * interface keeps the choice of backend out of the caller so later rungs slot in without touching the monitor
 * service. The spec requirement stays transport-agnostic; only this seam and the backend order are local.
 */
interface MotionAlertTransport {
    /** Delivers [alert]; a failure is the transport error, never a thrown exception (cancellation aside). */
    suspend fun send(alert: MotionAlert): Result<Unit>
}
