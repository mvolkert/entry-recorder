package io.github.mvolkert.entryrecorder.push

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.annotations.SerializedName
import io.github.mvolkert.entryrecorder.data.local.prefs.MotionAlertPushStore
import io.github.mvolkert.entryrecorder.domain.alert.RemoteMotionAlert
import io.github.mvolkert.entryrecorder.domain.alert.RemoteMotionAlertPresenter
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * The receiving leg of `add-motion-notification-to-other-phone`: turns the bytes the distributor delivered into
 * one [RemoteMotionAlert] and hands it to the presenter, and remembers the registration state the settings
 * screen shows.
 *
 * This is the seam the unit tests drive: it holds no [android.content.Context] and no connector type — the push
 * service passes [org.unifiedpush.android.connector.dataclass.PushMessage] content straight through as bytes —
 * so what a payload must contain is decided here, device-free. Anything that is not exactly the contract the
 * Python server POSTs is dropped: guessing at a body somebody else produced would invent an alert that never
 * happened. The raw content is never logged, because the payload arrives on a channel that any installed
 * distributor could be reading.
 */
@Singleton
class RemoteMotionAlertHandler @Inject constructor(
    private val store: MotionAlertPushStore,
    private val presenter: RemoteMotionAlertPresenter,
    private val gson: Gson,
) {

    /** Records the address the distributor handed this install, and whether it needs encrypted delivery. */
    fun onEndpoint(url: String, needsEncryption: Boolean) {
        store.saveEndpoint(url, needsEncryption)
        Timber.tag(tag).i("Motion-alert endpoint registered (needs encryption: $needsEncryption)")
    }

    /** The distributor says this registration is gone: forget the address so the settings screen says so. */
    fun onUnregistered() {
        store.clearEndpoint()
        Timber.tag(tag).i("Motion-alert registration removed")
    }

    /** Registration failed: drop a stale address rather than show one that cannot receive anything. */
    fun onRegistrationFailed(reason: String) {
        store.clearEndpoint()
        Timber.tag(tag).w("Motion-alert registration failed: $reason")
    }

    /**
     * One delivered message. Undecrypted content and a payload that is not the motion-alert contract produce
     * nothing; an opted-out phone ignores its registration entirely, because the distributor may still be
     * holding an address the user stopped wanting.
     */
    fun onAlert(content: ByteArray, decrypted: Boolean) {
        if (!store.isReceiveEnabled()) return
        if (!decrypted) {
            Timber.tag(tag).w("Ignored a motion alert the distributor did not decrypt")
            return
        }
        val alert = parseRemoteMotionAlert(content, gson) ?: return
        presenter.show(alert)
    }

    private companion object {
        const val tag = "RemoteMotionAlert"
    }
}

/**
 * The body the Python server POSTs to the recipient's push endpoint. [kind] is what identifies the payload as
 * one of this app's alerts: a distributor that fans several apps out over one topic must not have its other
 * traffic turned into a motion notification.
 */
internal data class RemoteMotionAlertPayload(
    @SerializedName("kind") val kind: String? = null,
    @SerializedName("deviceName") val deviceName: String? = null,
    @SerializedName("monitorName") val monitorName: String? = null,
    @SerializedName("timestampMs") val timestampMs: Long? = null,
)

/** The `kind` value the server stamps on every relayed alert; keep in step with `alert_relay.MOTION_ALERT_KIND`. */
internal const val MOTION_ALERT_KIND = "entry_recorder.motion"

/**
 * Parses the delivered bytes, returning null — after logging only the reason, never the body — for anything that
 * is not a motion alert: unparseable JSON, another `kind`, or a missing camera name. A payload without a
 * timestamp is still a real alert, so it is shown as having arrived now rather than discarded.
 */
internal fun parseRemoteMotionAlert(
    content: ByteArray,
    gson: Gson,
    nowMs: Long = System.currentTimeMillis(),
): RemoteMotionAlert? {
    val payload = try {
        gson.fromJson(content.toString(Charsets.UTF_8), RemoteMotionAlertPayload::class.java)
    } catch (e: JsonParseException) {
        Timber.tag("RemoteMotionAlert").w("Ignored a payload that is not JSON (${e.message})")
        return null
    } catch (e: IllegalStateException) {
        // Gson reads a top-level array or a bare value through the same object adapter: shape, not content.
        Timber.tag("RemoteMotionAlert").w("Ignored a payload that is not a JSON object (${e.message})")
        return null
    }
    if (payload == null) {
        Timber.tag("RemoteMotionAlert").w("Ignored an empty motion-alert payload")
        return null
    }
    if (payload.kind != MOTION_ALERT_KIND) {
        Timber.tag("RemoteMotionAlert").w("Ignored a payload with an unexpected kind: ${payload.kind ?: "<none>"}")
        return null
    }
    val deviceName = payload.deviceName?.trim().orEmpty()
    if (deviceName.isEmpty()) {
        Timber.tag("RemoteMotionAlert").w("Ignored a motion alert that names no camera")
        return null
    }
    return RemoteMotionAlert(
        deviceName = deviceName,
        monitorName = payload.monitorName?.trim().orEmpty(),
        timestampMs = payload.timestampMs?.takeIf { it > 0L } ?: nowMs,
    )
}
