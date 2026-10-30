package io.github.mvolkert.entryrecorder.domain.alert

/**
 * What this install knows about its own push registration, as the settings screen shows it (change
 * `add-motion-notification-to-other-phone`).
 *
 * [endpointUrl] is the address the user copies onto the monitoring phone. It carries a bearer token, so it is
 * shown for copying but never written to a log.
 */
data class MotionAlertPushState(
    val receiveEnabled: Boolean = false,
    val endpointUrl: String? = null,
    val needsEncryption: Boolean = false,
    val distributorAvailable: Boolean = false,
)
