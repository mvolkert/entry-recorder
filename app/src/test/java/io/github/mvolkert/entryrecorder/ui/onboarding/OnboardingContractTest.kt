package io.github.mvolkert.entryrecorder.ui.onboarding

import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.testing.assertEquals
import io.github.mvolkert.entryrecorder.testing.assertFalse
import io.github.mvolkert.entryrecorder.testing.assertTrue
import org.junit.Test

/**
 * The first-run flow's action table, checked without a device: which pages exist on which API level, and
 * what each page's two button slots say and do for every permission state. These are the rules that decide
 * whether a tap can reach the system sheet, so a wrong row here is a dead-end button on a phone.
 */
class OnboardingContractTest {

    @Test
    fun `pagesFor orders the flow and puts the asks between the two text pages`() {
        val pages = OnboardingContract.pagesFor(supportsNotifications = true)

        assertEquals(
            listOf(
                OnboardingStep.Welcome,
                OnboardingStep.Ask(OnboardingPermission.Notifications),
                OnboardingStep.Ask(OnboardingPermission.Microphone),
                OnboardingStep.Battery,
                OnboardingStep.Finish,
            ),
            pages,
        )
    }

    @Test
    fun `pagesFor drops the notifications ask below API 33 instead of asking for it`() {
        val pages = OnboardingContract.pagesFor(supportsNotifications = false)

        assertFalse(
            pages.contains(OnboardingStep.Ask(OnboardingPermission.Notifications)),
            "a permission that does not exist cannot be asked for",
        )
        assertEquals(4, pages.size, "the step counter's denominator is this list's size")
        assertEquals(
            OnboardingStep.Ask(OnboardingPermission.Microphone),
            pages[1],
            "the microphone ask moves up to fill the dropped page",
        )
    }

    @Test
    fun `an index the page list does not have lands on the first page, never on the last`() {
        val pages = OnboardingContract.pagesFor(supportsNotifications = false)

        assertEquals(OnboardingStep.Welcome, OnboardingContract.stepAt(pages, pages.size))
        assertEquals(OnboardingStep.Welcome, OnboardingContract.stepAt(pages, -1))
        assertTrue(
            OnboardingContract.primaryAction(OnboardingStep.Welcome).target == OnboardingTarget.Advance,
            "the fallback must not be the page that records a completion answer and closes the flow",
        )
    }

    @Test
    fun `the stored ask keys are fixed strings, not the enum names`() {
        assertEquals(
            listOf("notifications", "microphone"),
            listOf(OnboardingPermission.Notifications.prefsKey, OnboardingPermission.Microphone.prefsKey),
            "a rename of either constant would otherwise reset every installed denial counter to zero",
        )
    }

    @Test
    fun `granted ask shows an inert receipt and asks for nothing`() {
        val action = OnboardingContract.primaryAction(
            step = OnboardingStep.Ask(OnboardingPermission.Microphone),
            grant = GrantState.Granted,
        )

        assertEquals(R.string.onboarding_action_allowed, action.labelRes)
        assertEquals(OnboardingTarget.Advance, action.target)
        assertFalse(action.enabled, "there is nothing left to do, so the button must not look live")
        assertTrue(action.completed, "the check glyph is what tells the receipt from a dead control")
    }

    @Test
    fun `permanently denied ask routes to phone settings instead of re-firing a silent denial`() {
        val action = OnboardingContract.primaryAction(
            step = OnboardingStep.Ask(OnboardingPermission.Notifications),
            grant = GrantState.SettingsOnly,
        )

        assertEquals(R.string.onboarding_action_open_phone_settings, action.labelRes)
        assertEquals(OnboardingTarget.AppSettings, action.target)
        assertTrue(action.enabled)
    }

    @Test
    fun `each requestable ask names the permission its sheet will actually ask for`() {
        val notifications = OnboardingContract.primaryAction(
            OnboardingStep.Ask(OnboardingPermission.Notifications),
            GrantState.Requestable,
        )
        val microphone = OnboardingContract.primaryAction(
            OnboardingStep.Ask(OnboardingPermission.Microphone),
            GrantState.Requestable,
        )

        assertEquals(R.string.onboarding_action_grant_notifications, notifications.labelRes)
        assertEquals(R.string.onboarding_action_grant_microphone, microphone.labelRes)
        assertEquals(OnboardingTarget.RequestPermission, notifications.target)
        assertEquals(OnboardingTarget.RequestPermission, microphone.target)
    }

    @Test
    fun `an unresolved grant state falls back to asking rather than stranding the page`() {
        val action = OnboardingContract.primaryAction(OnboardingStep.Ask(OnboardingPermission.Microphone))

        assertEquals(
            OnboardingTarget.RequestPermission,
            action.target,
            "a missing entry has to offer the sheet again, not a settings screen nobody was denied",
        )
    }

    @Test
    fun `battery page offers the settings list and reads as done once exempted`() {
        val ask = OnboardingContract.primaryAction(OnboardingStep.Battery, batteryExempt = false)
        val exempt = OnboardingContract.primaryAction(OnboardingStep.Battery, batteryExempt = true)

        assertEquals(R.string.onboarding_action_open_battery_settings, ask.labelRes)
        assertEquals(OnboardingTarget.BatterySettings, ask.target)
        assertEquals(R.string.onboarding_action_allowed, exempt.labelRes)
        assertFalse(exempt.enabled)
        assertEquals(OnboardingTarget.Advance, exempt.target)
    }

    @Test
    fun `finish page takes the user to the device form`() {
        val action = OnboardingContract.primaryAction(OnboardingStep.Finish)

        assertEquals(R.string.onboarding_action_add_device, action.labelRes)
        assertEquals(OnboardingTarget.FinishOnDevices, action.target)
    }

    @Test
    fun `every page carries a secondary slot so the filled button never moves`() {
        val secondary = OnboardingContract.pagesFor(supportsNotifications = true).map { step ->
            OnboardingContract.secondaryAction(
                step = step,
                grant = if (step is OnboardingStep.Ask) GrantState.Requestable else null,
            )
        }

        assertTrue(secondary.all { it.enabled }, "a disabled decline would leave one live slot")
        assertEquals(OnboardingTarget.SkipFlow, secondary.first().target)
        assertEquals(OnboardingTarget.FinishOnLive, secondary.last().target)
    }

    @Test
    fun `a satisfied ask moves the way forward into the secondary slot`() {
        val declined = OnboardingContract.secondaryAction(
            step = OnboardingStep.Ask(OnboardingPermission.Microphone),
            grant = GrantState.Requestable,
        )
        val granted = OnboardingContract.secondaryAction(
            step = OnboardingStep.Ask(OnboardingPermission.Microphone),
            grant = GrantState.Granted,
        )

        assertEquals(R.string.onboarding_action_not_now, declined.labelRes)
        assertEquals(R.string.onboarding_action_continue, granted.labelRes)
        assertEquals(OnboardingTarget.Advance, granted.target, "the primary button is inert there")
    }

    @Test
    fun `no two pages share their title or body`() {
        val copies = OnboardingContract.pagesFor(supportsNotifications = true).map { OnboardingContract.copyFor(it) }

        assertEquals(copies.size, copies.map { it.titleRes }.distinct().size, "a copied title would draw two alike pages")
        assertEquals(copies.size, copies.map { it.bodyRes }.distinct().size)
        assertTrue(copies.none { it.titleRes == 0 || it.bodyRes == 0 }, "every page resolves to real copy")
    }

    @Test
    fun `a fresh install owes the flow and an answered one does not`() {
        assertTrue(shouldShowOnboarding(hasAnswer = false, isCompleted = false, upgradedInPlace = false))
        assertFalse(shouldShowOnboarding(hasAnswer = true, isCompleted = true, upgradedInPlace = false))
        assertTrue(
            shouldShowOnboarding(hasAnswer = true, isCompleted = false, upgradedInPlace = true),
            "re-running the intro from Settings is the only way back to a half-answered flow",
        )
    }

    @Test
    fun `an install that predates this build is not toured again`() {
        assertFalse(
            shouldShowOnboarding(hasAnswer = false, isCompleted = false, upgradedInPlace = true),
            "the launch-time modal already asked for these same two permissions and recorded nothing",
        )
    }

    @Test
    fun `every action label the table can produce is a string the app ships`() {
        val steps = OnboardingContract.pagesFor(supportsNotifications = true)
        val grants = listOf(null, GrantState.Granted, GrantState.Requestable, GrantState.SettingsOnly)
        val labels = buildList {
            for (step in steps) {
                for (grant in grants) {
                    for (exempt in listOf(false, true)) {
                        add(OnboardingContract.primaryAction(step, grant, exempt).labelRes)
                        add(OnboardingContract.secondaryAction(step, grant, exempt).labelRes)
                    }
                }
            }
        }

        val known = setOf(
            R.string.onboarding_action_start,
            R.string.onboarding_action_allowed,
            R.string.onboarding_action_grant_notifications,
            R.string.onboarding_action_grant_microphone,
            R.string.onboarding_action_open_phone_settings,
            R.string.onboarding_action_open_battery_settings,
            R.string.onboarding_action_add_device,
            R.string.onboarding_action_skip_setup,
            R.string.onboarding_action_continue,
            R.string.onboarding_action_not_now,
            R.string.onboarding_action_leave_as_is,
            R.string.onboarding_action_browse_without_device,
        )
        assertTrue(
            labels.all { it in known },
            "an action outside the shipped label set would draw a raw id: " +
                labels.filterNot { it in known }.distinct(),
        )
    }
}
