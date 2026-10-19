package io.github.mvolkert.entryrecorder.ui.onboarding

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.data.local.prefs.OnboardingStore
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn

/** Everything the first-run screen draws, including the button slots it always shows. */
data class OnboardingUiState(
    val pages: List<OnboardingStep> = emptyList(),
    val currentPage: Int = 0,
    val grants: Map<OnboardingPermission, GrantState> = emptyMap(),
    val batteryExempt: Boolean = false,
    @StringRes val noticeRes: Int? = null,
) {
    val step: OnboardingStep = OnboardingContract.stepAt(pages, currentPage)

    val grantState: GrantState?
        get() = (step as? OnboardingStep.Ask)?.let { grants[it.permission] }

    val primaryAction: OnboardingAction =
        OnboardingContract.primaryAction(step, grantState, batteryExempt)

    val secondaryAction: OnboardingAction =
        OnboardingContract.secondaryAction(step, grantState, batteryExempt)

    /** The bottom navigation row, which depends on the page alone and never on a grant state. */
    val previousAction: OnboardingAction = OnboardingContract.previousAction(currentPage == 0)

    /** Forward motion out of every page but the last, whatever the user did or did not grant. */
    val nextAction: OnboardingAction = OnboardingContract.nextAction(currentPage >= pages.lastIndex)
}

/** The one-shot intents the screen has to carry out itself: its launchers, not the ViewModel's. */
sealed interface OnboardingEvent {
    /** Carries the domain permission, not the platform string — the screen resolves it through [platformName]. */
    data class RequestPermission(val permission: OnboardingPermission) : OnboardingEvent
    data object OpenAppSettings : OnboardingEvent
    data object OpenBatterySettings : OnboardingEvent

    /**
     * Move the pager to [index] because one of the flow's own buttons asked for it. A one-shot intent
     * rather than a state field: the pager owns position, the ViewModel owns where the buttons send it,
     * and a state value shared by both would be a second writer racing the user's swipe.
     */
    data class GoToPage(val index: Int) : OnboardingEvent
    data class Finished(val landOnDevices: Boolean) : OnboardingEvent
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val permissions: PermissionChecker,
    private val store: OnboardingStore,
) : ViewModel() {

    private val pages = OnboardingContract.pagesFor(permissions.supportsNotifications())

    private val currentPage = MutableStateFlow(0)
    private val grants = MutableStateFlow(resolveGrants())
    private val batteryExempt = MutableStateFlow(permissions.isBatteryExempt())
    private val noticeRes = MutableStateFlow<Int?>(null)

    /** Whether this run has already handed off; see [finish]. */
    private var finished = false

    val uiState: StateFlow<OnboardingUiState> =
        combine(currentPage, grants, batteryExempt, noticeRes) { page, grantMap, exempt, notice ->
            OnboardingUiState(
                pages = pages,
                currentPage = page,
                grants = grantMap,
                batteryExempt = exempt,
                noticeRes = notice,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, OnboardingUiState(pages = pages))

    private val _events = Channel<OnboardingEvent>(Channel.BUFFERED)

    /** Requests the screen fires through its own activity-result launchers. */
    val events: Flow<OnboardingEvent> = _events.receiveAsFlow()

    /** The platform name the system sheet is fired with, for [OnboardingEvent.RequestPermission]. */
    fun platformName(permission: OnboardingPermission): String = permissions.platformName(permission)

    /**
     * The pager settled on [index]. Reported upward only — it never bounces a scroll request back down, so
     * a fling across two pages cannot be pulled to a halt at the page in between.
     */
    fun onPageChanged(index: Int) {
        if (index == currentPage.value) return
        currentPage.value = index.coerceIn(0, pages.lastIndex)
        noticeRes.value = null
    }

    fun onPrimaryAction() = act(uiState.value.primaryAction)

    fun onSecondaryAction() = act(uiState.value.secondaryAction)

    /** The bottom row's way back: a page step only, never a permission ask and never a completion write. */
    fun onPreviousAction() = act(uiState.value.previousAction)

    /** The bottom row's way forward, live on every page that has a next one regardless of grant state. */
    fun onNextAction() = act(uiState.value.nextAction)

    /**
     * System back never leaves the flow: it walks one page, and on the first page it does what the
     * flow's own skip button does. Leaving mid-onboarding would strand an install that was never
     * finished, with no way back to the asks.
     */
    fun onBackPressed() {
        if (canGoBack()) goTo(currentPage.value - 1) else finish(landOnDevices = false)
    }

    /**
     * Re-reads the platform instead of trusting the answer the launcher reported: the user can flip a
     * toggle in phone settings between the sheet and the page, and a battery exemption is only ever
     * granted there.
     */
    fun onResume() {
        grants.value = resolveGrants()
        batteryExempt.value = permissions.isBatteryExempt()
    }

    /** Said out loud on the page rather than swallowed: some skins ship no battery-optimization list. */
    fun onSettingsScreenUnavailable() {
        noticeRes.value = R.string.onboarding_notice_settings_missing
    }

    private fun canGoBack(): Boolean = currentPage.value > 0

    private fun act(action: OnboardingAction) {
        if (!action.enabled) return
        when (action.target) {
            OnboardingTarget.Advance -> advance()
            OnboardingTarget.GoBack -> goTo(currentPage.value - 1)
            OnboardingTarget.GoForward -> goTo(currentPage.value + 1)
            OnboardingTarget.SkipFlow -> finish(landOnDevices = false)
            OnboardingTarget.FinishOnDevices -> finish(landOnDevices = true)
            OnboardingTarget.FinishOnLive -> finish(landOnDevices = false)
            OnboardingTarget.RequestPermission -> requestPermission()
            OnboardingTarget.AppSettings -> _events.trySend(OnboardingEvent.OpenAppSettings)
            OnboardingTarget.BatterySettings -> _events.trySend(OnboardingEvent.OpenBatterySettings)
        }
    }

    private fun advance() {
        val next = currentPage.value + 1
        if (next > pages.lastIndex) finish(landOnDevices = true) else goTo(next)
    }

    /** Changes the page on the flow's own initiative, so the pager is told to move rather than inferred. */
    private fun goTo(index: Int) {
        val target = index.coerceIn(0, pages.lastIndex)
        if (target == currentPage.value) return
        currentPage.value = target
        noticeRes.value = null
        _events.trySend(OnboardingEvent.GoToPage(target))
    }

    private fun requestPermission() {
        val step = uiState.value.step as? OnboardingStep.Ask ?: return
        permissions.recordAsk(step.permission)
        _events.trySend(OnboardingEvent.RequestPermission(step.permission))
    }

    /**
     * One answer per run: [finished] is what stops a second tap in the same frame from writing the flag
     * twice and buffering a second hand-off, which would move the tabs a page the user never chose.
     */
    private fun finish(landOnDevices: Boolean) {
        if (finished) return
        finished = true
        store.setCompleted(true)
        _events.trySend(OnboardingEvent.Finished(landOnDevices))
    }

    private fun resolveGrants(): Map<OnboardingPermission, GrantState> =
        pages.filterIsInstance<OnboardingStep.Ask>()
            .associate { it.permission to permissions.grantState(it.permission) }
}
