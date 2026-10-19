@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.mvolkert.entryrecorder.ui.onboarding

import app.cash.turbine.test
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.testing.FakeOnboardingStore
import io.github.mvolkert.entryrecorder.testing.FakePermissionChecker
import io.github.mvolkert.entryrecorder.testing.assertNone
import io.github.mvolkert.entryrecorder.testing.assertEquals
import io.github.mvolkert.entryrecorder.testing.assertFalse
import io.github.mvolkert.entryrecorder.testing.assertTrue
import io.github.mvolkert.entryrecorder.testing.expectIs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The first-run flow's state machine, off a device: which page is showing, what its two slots do, how an
 * answer that arrived from the system sheet or from phone settings changes them, and that ending the flow
 * records exactly one answer. The permission and preferences seams are the fakes, so nothing here reads a
 * real API level.
 */
class OnboardingViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var permissions: FakePermissionChecker
    private lateinit var store: FakeOnboardingStore

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        permissions = FakePermissionChecker()
        store = FakeOnboardingStore()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Builds the flow after the test has configured [permissions], so the page list is the one being proved. */
    private fun viewModel(): OnboardingViewModel = OnboardingViewModel(permissions, store)

    /** Runs [body] inside runTest sharing [testDispatcher]'s scheduler, then flushes and leaves. */
    private fun runViewModelTest(body: suspend TestScope.() -> Unit) =
        runTest(StandardTestDispatcher(testDispatcher.scheduler)) {
            body()
            advanceUntilIdle()
        }

    /** Moves the flow to [index] the way a swipe reports in, then lets the derived state settle. */
    private suspend fun TestScope.goTo(viewModel: OnboardingViewModel, index: Int) {
        viewModel.onPageChanged(index)
        advanceUntilIdle()
    }

    /** Moves the flow to [index] the way the flow's own buttons do, which also ask the pager to scroll. */
    private suspend fun TestScope.pressForward(viewModel: OnboardingViewModel) {
        viewModel.onPrimaryAction()
        advanceUntilIdle()
    }

    @Test
    fun `the flow opens on the welcome page with one way forward`() = runViewModelTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(5, state.pages.size, "welcome, both asks, battery, ready")
        assertEquals(0, state.currentPage)
        assertEquals(OnboardingStep.Welcome, state.step)
        assertEquals(R.string.onboarding_action_start, state.primaryAction.labelRes)
        assertEquals(OnboardingTarget.SkipFlow, state.secondaryAction.target)
    }

    @Test
    fun `the notifications page is absent below API 33 and the counter follows`() = runViewModelTest {
        permissions.notificationsSupported = false
        val viewModel = viewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(4, state.pages.size)
        assertFalse(state.pages.contains(OnboardingStep.Ask(OnboardingPermission.Notifications)))

        goTo(viewModel, 1)
        assertEquals(
            OnboardingStep.Ask(OnboardingPermission.Microphone),
            viewModel.uiState.value.step,
            "the pages stay contiguous so the step counter never names a page that is not there",
        )
    }

    @Test
    fun `a granted ask re-resolves to the inert receipt after the resume refresh`() = runViewModelTest {
        val viewModel = viewModel()
        goTo(viewModel, 1)
        assertEquals(
            R.string.onboarding_action_grant_notifications,
            viewModel.uiState.value.primaryAction.labelRes,
        )

        permissions.states[OnboardingPermission.Notifications] = GrantState.Granted
        viewModel.onResume()
        advanceUntilIdle()

        val action = viewModel.uiState.value.primaryAction
        assertEquals(R.string.onboarding_action_allowed, action.labelRes)
        assertTrue(action.completed)
        assertFalse(action.enabled, "the receipt must not look like a control that stopped working")
        assertEquals(
            R.string.onboarding_action_continue,
            viewModel.uiState.value.secondaryAction.labelRes,
            "with the filled button inert, the way forward moves to the second slot",
        )
    }

    @Test
    fun `an ask fires only its own permission and records that it went out`() = runViewModelTest {
        val viewModel = viewModel()
        goTo(viewModel, 1)

        viewModel.events.test {
            viewModel.onPrimaryAction()
            advanceUntilIdle()

            val event = expectIs<OnboardingEvent.RequestPermission>(awaitItem())
            // The event carries the domain permission; the platform string is resolved where the sheet is
            // fired, so nothing in the flow's state depends on a manifest constant.
            assertEquals(OnboardingPermission.Notifications, event.permission)
            assertEquals("platform-Notifications", viewModel.platformName(event.permission))
            assertEquals(listOf(OnboardingPermission.Notifications), permissions.recordedAsks)
            expectNoEvents()
        }
    }

    @Test
    fun `a swipe is reported without an intent back down, and a button asks the pager to move`() =
        runViewModelTest {
            val viewModel = viewModel()

            viewModel.events.test {
                goTo(viewModel, 2)
                expectNoEvents()

                goTo(viewModel, 0)
                expectNoEvents()

                // The welcome page's primary action is the one that only moves the flow along.
                pressForward(viewModel)
                assertEquals(
                    OnboardingEvent.GoToPage(1),
                    awaitItem(),
                    "the page the buttons chose has to reach the pager as a one-shot, not as a second owner",
                )
                expectNoEvents()
            }
        }

    @Test
    fun `a denied-twice ask opens phone settings instead of a sheet that no longer appears`() = runViewModelTest {
        val viewModel = viewModel()
        goTo(viewModel, 1)
        permissions.states[OnboardingPermission.Notifications] = GrantState.SettingsOnly
        viewModel.onResume()
        advanceUntilIdle()

        viewModel.events.test {
            viewModel.onPrimaryAction()
            advanceUntilIdle()

            assertEquals(OnboardingEvent.OpenAppSettings, awaitItem())
            assertTrue(permissions.recordedAsks.isEmpty(), "a settings hop is not another ask")
            expectNoEvents()
        }
    }

    @Test
    fun `a settings screen the skin does not ship is said on the page and cleared by the next one`() =
        runViewModelTest {
            val viewModel = viewModel()

            viewModel.onSettingsScreenUnavailable()
            advanceUntilIdle()
            assertEquals(
                R.string.onboarding_notice_settings_missing,
                viewModel.uiState.value.noticeRes,
                "no snack bar host exists above this screen, so the reason has to be inline",
            )

            viewModel.onPageChanged(2)
            advanceUntilIdle()
            assertNone(viewModel.uiState.value.noticeRes, "the notice belongs to the page that earned it")
        }

    @Test
    fun `battery exemption turns its page into a receipt without leaving the flow`() = runViewModelTest {
        val viewModel = viewModel()
        goTo(viewModel, 3)
        assertEquals(OnboardingTarget.BatterySettings, viewModel.uiState.value.primaryAction.target)

        permissions.batteryExempt = true
        viewModel.onResume()
        advanceUntilIdle()

        assertEquals(R.string.onboarding_action_allowed, viewModel.uiState.value.primaryAction.labelRes)
        viewModel.onSecondaryAction()
        advanceUntilIdle()
        assertEquals(4, viewModel.uiState.value.currentPage, "the decline slot carries the way past it")
    }

    @Test
    fun `finishing records one answer and hands off once, however many taps arrive`() = runViewModelTest {
        val viewModel = viewModel()
        goTo(viewModel, viewModel.uiState.value.pages.lastIndex)

        viewModel.events.test {
            viewModel.onPrimaryAction()
            viewModel.onPrimaryAction()
            advanceUntilIdle()

            val finished = expectIs<OnboardingEvent.Finished>(awaitItem())
            assertTrue(finished.landOnDevices)
            assertEquals(1, store.setCompletedCount, "one completion, however many taps reach it")
            assertTrue(store.completedAnswer)
            expectNoEvents()
        }
    }

    @Test
    fun `skipping from the first page ends the flow on the live tab`() = runViewModelTest {
        val viewModel = viewModel()

        viewModel.events.test {
            viewModel.onSecondaryAction()
            advanceUntilIdle()

            val finished = expectIs<OnboardingEvent.Finished>(awaitItem())
            assertFalse(finished.landOnDevices)
            assertTrue(store.completedAnswer, "a skipped intro is still an answer; it must not come back next open")
            expectNoEvents()
        }
    }

    @Test
    fun `the nav row pages the flow without asking for anything or answering for the user`() =
        runViewModelTest {
            val viewModel = viewModel()
            // A permission that has stopped offering its sheet leaves the forward slot exactly as live as
            // an allowed one does: the row reads the page index and nothing else.
            permissions.states[OnboardingPermission.Notifications] = GrantState.SettingsOnly
            viewModel.onResume()
            advanceUntilIdle()

            viewModel.events.test {
                viewModel.onNextAction()
                advanceUntilIdle()
                assertEquals(OnboardingEvent.GoToPage(1), awaitItem())
                assertEquals(1, viewModel.uiState.value.currentPage)

                viewModel.onPreviousAction()
                advanceUntilIdle()
                assertEquals(OnboardingEvent.GoToPage(0), awaitItem())

                assertTrue(permissions.recordedAsks.isEmpty(), "a page step never fires the system sheet")
                assertEquals(0, store.setCompletedCount, "a page step never records an answer")
                expectNoEvents()
            }
        }

    @Test
    fun `the nav row does nothing at either end rather than wrapping or finishing`() = runViewModelTest {
        val viewModel = viewModel()

        viewModel.events.test {
            viewModel.onPreviousAction()
            advanceUntilIdle()
            assertEquals(0, viewModel.uiState.value.currentPage, "the first page has no page before it")
            expectNoEvents()

            goTo(viewModel, viewModel.uiState.value.pages.lastIndex)
            assertFalse(
                viewModel.uiState.value.nextAction.enabled,
                "the last page hands finishing to the two slots above the nav row",
            )
            viewModel.onNextAction()
            advanceUntilIdle()

            expectNoEvents()
            assertEquals(0, store.setCompletedCount, "walking into the end of the flow is not an answer")
        }
    }

    @Test
    fun `back walks one page and only declines the flow from the first`() = runViewModelTest {
        val viewModel = viewModel()

        viewModel.events.test {
            goTo(viewModel, 2)
            expectNoEvents()

            viewModel.onBackPressed()
            advanceUntilIdle()
            assertEquals(OnboardingEvent.GoToPage(1), awaitItem())
            assertEquals(1, viewModel.uiState.value.currentPage)

            goTo(viewModel, 0)
            viewModel.onBackPressed()
            advanceUntilIdle()

            val finished = expectIs<OnboardingEvent.Finished>(awaitItem())
            assertFalse(finished.landOnDevices)
            expectNoEvents()
        }
    }
}
