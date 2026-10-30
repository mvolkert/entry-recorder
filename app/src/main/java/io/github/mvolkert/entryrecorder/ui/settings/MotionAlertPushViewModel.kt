package io.github.mvolkert.entryrecorder.ui.settings

import android.app.Activity
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mvolkert.entryrecorder.data.local.prefs.MotionAlertPushStore
import io.github.mvolkert.entryrecorder.domain.alert.MotionAlertPushState
import io.github.mvolkert.entryrecorder.push.MotionAlertRegistrar
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The receiver half of the motion-alert settings (change `add-motion-notification-to-other-phone`): whether this
 * phone is opted in, what address its distributor handed it, and what the screen has to say when there is none.
 *
 * A ViewModel of its own rather than more members on [SettingsViewModel]: the state it shows is written by the
 * push service from a binder thread into SharedPreferences, not by Room, so it needs none of that class's
 * repository plumbing — and the settings row it drives is the monitor phone's, not this phone's. Like
 * [SettingsViewModel] it holds no [android.content.Context]: the transient [Activity] the distributor picker
 * needs is passed into [setReceiveEnabled] by the screen and never stored.
 */
@HiltViewModel
class MotionAlertPushViewModel @Inject constructor(
    private val store: MotionAlertPushStore,
    private val registrar: MotionAlertRegistrar,
) : ViewModel() {

    private val _state = MutableStateFlow(readState())

    /** The receiver state to render. Fresh on every read, so a late endpoint shows up on the next [refresh]. */
    val state: StateFlow<MotionAlertPushState> = _state.asStateFlow()

    /** Re-reads the stored registration; the screen calls it when it is shown again. */
    fun refresh() {
        _state.value = readState()
    }

    /**
     * Turns receiving alerts on or off. Opting in is what attaches the phone to a push app — a user-visible
     * side effect, so it never happens on its own — and opting out drops the registration as well as the flag,
     * so the address the user may have already handed out stops working.
     */
    fun setReceiveEnabled(enabled: Boolean, activity: Activity?) {
        store.setReceiveEnabled(enabled)
        if (enabled) {
            // The endpoint arrives asynchronously through the push service, so the screen is refreshed from
            // the callback and stays honest about "no address yet" until it does.
            registrar.register(activity) { refresh() }
        } else {
            registrar.unregister()
            store.clearEndpoint()
        }
        refresh()
    }

    private fun readState() = MotionAlertPushState(
        receiveEnabled = store.isReceiveEnabled(),
        endpointUrl = store.endpoint(),
        needsEncryption = store.endpointNeedsEncryption(),
        distributorAvailable = registrar.hasDistributor(),
    )
}
