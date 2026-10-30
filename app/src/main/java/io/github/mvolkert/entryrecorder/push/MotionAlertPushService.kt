package io.github.mvolkert.entryrecorder.push

import dagger.hilt.android.AndroidEntryPoint
import io.github.mvolkert.entryrecorder.util.redactBearerUrl
import javax.inject.Inject
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import timber.log.Timber

/**
 * The component a UnifiedPush distributor delivers this app's motion alerts to (change
 * `add-motion-notification-to-other-phone`).
 *
 * Declared non-exported with the connector's `PUSH_EVENT` action: the distributor reaches it through a bound
 * service rather than an implicit broadcast, so nothing on the phone can post a fake alert at it. Every callback
 * delegates to [RemoteMotionAlertHandler] — this class only adapts the connector's types, which is why it can be
 * as thin as it is. The endpoint URL is logged redacted: it carries the token that lets its holder push to this
 * phone, so only `scheme://host` is safe to leave in a log file.
 */
@AndroidEntryPoint
class MotionAlertPushService : PushService() {

    @Inject
    lateinit var handler: RemoteMotionAlertHandler

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        Timber.tag(tag).i("Registered for instance $instance at ${redactBearerUrl(endpoint.url)}")
        // A Web Push endpoint (keys present) encrypts its bodies, which a plain relay POST cannot produce, so
        // the flag is stored for the settings screen to say so instead of offering a useless address.
        handler.onEndpoint(url = endpoint.url, needsEncryption = endpoint.pubKeySet != null)
    }

    override fun onMessage(message: PushMessage, instance: String) {
        handler.onAlert(content = message.content, decrypted = message.decrypted)
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        handler.onRegistrationFailed(reason.name)
    }

    override fun onUnregistered(instance: String) = handler.onUnregistered()

    /** The distributor is briefly unreachable: worth a line, but the registration itself still stands. */
    override fun onTempUnavailable(instance: String) {
        Timber.tag(tag).w("Distributor is temporarily unavailable for instance $instance")
    }

    private companion object {
        const val tag = "MotionAlertPush"
    }
}
