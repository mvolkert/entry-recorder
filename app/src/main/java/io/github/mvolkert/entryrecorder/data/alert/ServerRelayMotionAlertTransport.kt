package io.github.mvolkert.entryrecorder.data.alert

import io.github.mvolkert.entryrecorder.data.server.ServerClient
import io.github.mvolkert.entryrecorder.domain.alert.MotionAlert
import io.github.mvolkert.entryrecorder.domain.alert.MotionAlertTransport
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The primary rung of the motion-alert ladder (change `add-motion-notification-to-other-phone`): relay the
 * alert through the already-authenticated Python server, which reaches the other phone behind NAT without any
 * new client dependency. It delegates straight to [ServerClient.relayMotionAlert] — the server coordinates
 * delivery, and the recipient actually receiving it is the on-device gate (task 3.2).
 *
 * UnifiedPush and FCM would be later rungs behind the same [MotionAlertTransport] seam; they are Ask-First
 * dependencies, so this server-relay backend is the only implementation for now.
 */
@Singleton
class ServerRelayMotionAlertTransport @Inject constructor(
    private val serverClient: ServerClient,
) : MotionAlertTransport {
    override suspend fun send(alert: MotionAlert): Result<Unit> =
        serverClient.relayMotionAlert(
            serverUrl = alert.serverBaseUrl,
            apiKey = alert.apiKey,
            recipient = alert.recipient,
            deviceName = alert.deviceName,
            timestampMs = alert.timestampMs,
        )
}
