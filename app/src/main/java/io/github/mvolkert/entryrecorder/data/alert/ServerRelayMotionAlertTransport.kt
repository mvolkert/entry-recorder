package io.github.mvolkert.entryrecorder.data.alert

import io.github.mvolkert.entryrecorder.data.server.ServerClient
import io.github.mvolkert.entryrecorder.domain.alert.MotionAlert
import io.github.mvolkert.entryrecorder.domain.alert.MotionAlertTransport
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The rung of the motion-alert ladder (change `add-motion-notification-to-other-phone`) the monitoring phone
 * uses: hand the alert to the already-authenticated Python server, which performs the final hop to the
 * recipient's UnifiedPush endpoint. The phone gains no second outbound destination, so the network-security
 * posture and the single origin-keyed server link stay exactly as they are; whether the recipient's phone
 * buzzes is the device gate.
 *
 * A failed [Result] here is a delivery outcome, not a link statement: 400 means the server refused the address
 * and 502 means the recipient's push distributor rejected the alert — both from a server that answered. Only an
 * [java.io.IOException] says the server could not be reached, and [ServerClient.relayMotionAlert] reports that
 * to the shared link clock.
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
