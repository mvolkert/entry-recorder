package io.github.mvolkert.entryrecorder.ui.onboarding

import androidx.annotation.StringRes
import io.github.mvolkert.entryrecorder.R

/**
 * A runtime permission the first-run flow can ask for. [prefsKey] is the identity the ask counter is
 * stored under, deliberately separate from the enum name: renaming a constant must not silently reset
 * every installed counter and re-arm a prompt the system no longer shows.
 */
enum class OnboardingPermission(val prefsKey: String) {
    Notifications("notifications"),
    Microphone("microphone"),
}

/** Where one ask currently stands on this device. */
sealed interface GrantState {

    /** The permission is held; nothing has to be asked for. */
    data object Granted : GrantState

    /** Not held, and the system sheet will still appear if asked. */
    data object Requestable : GrantState

    /**
     * Not held and the system no longer shows its sheet for this permission — asking again returns a
     * silent denial, so only the phone's own settings can fix it.
     */
    data object SettingsOnly : GrantState
}

/** One page of the flow. */
sealed interface OnboardingStep {
    data object Welcome : OnboardingStep
    data class Ask(val permission: OnboardingPermission) : OnboardingStep
    data object Battery : OnboardingStep
    data object Finish : OnboardingStep
}

/** What a footer button does when pressed — the entire action surface of the flow. */
enum class OnboardingTarget {
    Advance,
    SkipFlow,
    RequestPermission,
    AppSettings,
    BatterySettings,
    FinishOnDevices,
    FinishOnLive,
}

/**
 * One of the two button slots. [completed] is the satisfied-ask variant: it draws the check glyph, so
 * "Allowed" reads as a result rather than as a control that stopped working.
 */
data class OnboardingAction(
    @StringRes val labelRes: Int,
    val target: OnboardingTarget,
    val enabled: Boolean = true,
    val completed: Boolean = false,
)

/**
 * The two text blocks one page draws. Kept in the table rather than in the screen so a new page cannot
 * be added without copy: [copyFor] has no default branch to fall through to.
 */
data class OnboardingCopy(
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int,
)

/**
 * Whether the window owes the first-run flow.
 *
 * Two facts answer it: the flow's own recorded answer, and whether the install predates this build. The
 * launch-time modal this flow replaces recorded nothing at all — it gated on in-memory state only — so an
 * in-place upgrade is the durable signal that an install has been asked before, and a tour that re-asks
 * these same two permissions is the nag that modal was criticized for. Re-running the intro from Settings
 * clears [isCompleted], which is what puts the flow back on screen.
 */
fun shouldShowOnboarding(
    hasAnswer: Boolean,
    isCompleted: Boolean,
    upgradedInPlace: Boolean,
): Boolean = when {
    hasAnswer -> !isCompleted
    upgradedInPlace -> false
    else -> true
}

/**
 * The flow's shape and its action table, with no Android types in it: the page list, what each page's
 * two buttons say and do, and how a permission state maps onto them. Everything the first-run screens
 * draw is decided here so the rules are checkable without a device.
 */
object OnboardingContract {

    /**
     * The page order. The notifications ask only exists from Android 13 up, and is left out entirely
     * rather than shown as an ask that cannot be made, so the step counter keeps naming real pages.
     */
    fun pagesFor(supportsNotifications: Boolean): List<OnboardingStep> = buildList {
        add(OnboardingStep.Welcome)
        if (supportsNotifications) add(OnboardingStep.Ask(OnboardingPermission.Notifications))
        add(OnboardingStep.Ask(OnboardingPermission.Microphone))
        add(OnboardingStep.Battery)
        add(OnboardingStep.Finish)
    }

    /**
     * The page at [index]. An index the list does not have is a bug, so it resolves to the *first* page:
     * landing on [OnboardingStep.Finish] instead would record a completion answer and close the flow, which
     * is the one wrong answer that cannot be undone by the user.
     */
    fun stepAt(pages: List<OnboardingStep>, index: Int): OnboardingStep =
        pages.getOrElse(index) { OnboardingStep.Welcome }

    /** The copy for one page. */
    fun copyFor(step: OnboardingStep): OnboardingCopy = when (step) {
        OnboardingStep.Welcome -> OnboardingCopy(
            R.string.onboarding_welcome_title,
            R.string.onboarding_welcome_body,
        )

        is OnboardingStep.Ask -> when (step.permission) {
            OnboardingPermission.Notifications -> OnboardingCopy(
                R.string.onboarding_notifications_title,
                R.string.onboarding_notifications_body,
            )

            OnboardingPermission.Microphone -> OnboardingCopy(
                R.string.onboarding_microphone_title,
                R.string.onboarding_microphone_body,
            )
        }

        OnboardingStep.Battery -> OnboardingCopy(
            R.string.onboarding_battery_title,
            R.string.onboarding_battery_body,
        )

        OnboardingStep.Finish -> OnboardingCopy(
            R.string.onboarding_finish_title,
            R.string.onboarding_finish_body,
        )
    }

    /**
     * The primary action. An unresolved ask is treated as [GrantState.Requestable] so a missing entry
     * offers the system sheet again instead of stranding the page.
     */
    fun primaryAction(
        step: OnboardingStep,
        grant: GrantState? = null,
        batteryExempt: Boolean = false,
    ): OnboardingAction = when (step) {
        OnboardingStep.Welcome -> OnboardingAction(
            R.string.onboarding_action_start,
            OnboardingTarget.Advance,
        )

        is OnboardingStep.Ask -> when (grant) {
            GrantState.Granted -> OnboardingAction(
                R.string.onboarding_action_allowed,
                OnboardingTarget.Advance,
                enabled = false,
                completed = true,
            )

            GrantState.SettingsOnly -> OnboardingAction(
                R.string.onboarding_action_open_phone_settings,
                OnboardingTarget.AppSettings,
            )

            else -> OnboardingAction(
                labelRes = if (step.permission == OnboardingPermission.Notifications) {
                    R.string.onboarding_action_grant_notifications
                } else {
                    R.string.onboarding_action_grant_microphone
                },
                target = OnboardingTarget.RequestPermission,
            )
        }

        OnboardingStep.Battery -> if (batteryExempt) {
            OnboardingAction(
                R.string.onboarding_action_allowed,
                OnboardingTarget.Advance,
                enabled = false,
                completed = true,
            )
        } else {
            OnboardingAction(R.string.onboarding_action_open_battery_settings, OnboardingTarget.BatterySettings)
        }

        OnboardingStep.Finish -> OnboardingAction(
            R.string.onboarding_action_add_device,
            OnboardingTarget.FinishOnDevices,
        )
    }

    /**
     * The secondary slot, which is always composed so the primary button never moves. On a satisfied
     * page it carries the way forward, because the primary button is the inert receipt there.
     */
    fun secondaryAction(
        step: OnboardingStep,
        grant: GrantState? = null,
        batteryExempt: Boolean = false,
    ): OnboardingAction = when (step) {
        OnboardingStep.Welcome -> OnboardingAction(R.string.onboarding_action_skip_setup, OnboardingTarget.SkipFlow)

        is OnboardingStep.Ask -> OnboardingAction(
            labelRes = if (grant == GrantState.Granted) {
                R.string.onboarding_action_continue
            } else {
                R.string.onboarding_action_not_now
            },
            target = OnboardingTarget.Advance,
        )

        OnboardingStep.Battery -> OnboardingAction(
            labelRes = if (batteryExempt) {
                R.string.onboarding_action_continue
            } else {
                R.string.onboarding_action_leave_as_is
            },
            target = OnboardingTarget.Advance,
        )

        OnboardingStep.Finish -> OnboardingAction(
            R.string.onboarding_action_browse_without_device,
            OnboardingTarget.FinishOnLive,
        )
    }
}
