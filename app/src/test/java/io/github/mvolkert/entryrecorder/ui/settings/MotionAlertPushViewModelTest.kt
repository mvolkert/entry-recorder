package io.github.mvolkert.entryrecorder.ui.settings

import io.github.mvolkert.entryrecorder.testing.FakeMotionAlertPushStore
import io.github.mvolkert.entryrecorder.testing.FakeMotionAlertRegistrar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The receiver section of `add-motion-notification-to-other-phone`, driven through the prefs seam it actually
 * uses. Nothing here touches Room — the push address belongs to this install and this distributor, so a backup
 * restored onto another phone must not hand it a dead address; re-registering re-derives it instead.
 */
class MotionAlertPushViewModelTest {

    private val store = FakeMotionAlertPushStore()
    private val registrar = FakeMotionAlertRegistrar()
    private val viewModel = MotionAlertPushViewModel(store, registrar)

    @Test
    fun `turning the switch on persists through the prefs seam and registers`() {
        viewModel.setReceiveEnabled(true, activity = null)

        assertEquals(listOf(true), store.enabledWrites)
        assertEquals(1, registrar.registerCount)
        assertTrue(viewModel.state.value.receiveEnabled)
    }

    @Test
    fun `turning the switch off unregisters and drops the address it had handed out`() {
        store.enabled = true
        store.saveEndpoint("https://ntfy.sh/secret-token", needsEncryption = false)

        viewModel.setReceiveEnabled(false, activity = null)

        assertEquals(1, registrar.unregisterCount)
        assertEquals(1, store.clearCount)
        assertFalse(viewModel.state.value.receiveEnabled)
        assertNull(viewModel.state.value.endpointUrl)
    }

    @Test
    fun `an endpoint the push service stored later shows up on the next read`() {
        viewModel.setReceiveEnabled(true, activity = null)
        assertNull(viewModel.state.value.endpointUrl)

        // The distributor answers asynchronously through MotionAlertPushService; re-entering the screen picks it up.
        store.saveEndpoint("https://ntfy.sh/secret-token", needsEncryption = false)
        viewModel.refresh()

        assertEquals("https://ntfy.sh/secret-token", viewModel.state.value.endpointUrl)
        assertFalse(viewModel.state.value.needsEncryption)
    }

    @Test
    fun `a phone with no distributor installed says so instead of failing silently`() {
        registrar.distributorInstalled = false

        assertFalse(MotionAlertPushViewModel(store, registrar).state.value.distributorAvailable)
    }

    @Test
    fun `an encrypted-delivery endpoint is carried as a state the screen can warn about`() {
        store.saveEndpoint("https://unifiedpush.example/p/abc", needsEncryption = true)

        viewModel.refresh()

        assertTrue(viewModel.state.value.needsEncryption)
    }

    @Test
    fun `a registration the distributor refused leaves no address behind`() {
        registrar.registerResult = false

        viewModel.setReceiveEnabled(true, activity = null)

        assertEquals(1, registrar.registerCount)
        assertNull(viewModel.state.value.endpointUrl)
    }
}
