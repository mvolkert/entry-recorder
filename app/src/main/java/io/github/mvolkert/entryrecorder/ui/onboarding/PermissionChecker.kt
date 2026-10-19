package io.github.mvolkert.entryrecorder.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mvolkert.entryrecorder.data.local.prefs.OnboardingStore
import javax.inject.Inject

/**
 * The platform facts the first-run flow reads, behind a seam so its ViewModel is constructable off a
 * device — the reason [io.github.mvolkert.entryrecorder.util.UiStrings] exists for strings.
 */
interface PermissionChecker {

    /** Whether the runtime notification permission exists on this API level at all. */
    fun supportsNotifications(): Boolean

    fun grantState(permission: OnboardingPermission): GrantState

    /** Whether the phone already lets the monitor service run without battery management. */
    fun isBatteryExempt(): Boolean

    /** The platform name the system sheet is fired with. */
    fun platformName(permission: OnboardingPermission): String

    /** Records that the sheet went out for [permission], which is what eventually makes it [GrantState.SettingsOnly]. */
    fun recordAsk(permission: OnboardingPermission)
}

/** Device-backed [PermissionChecker] over the application context and the flow's own ask counter. */
class AndroidPermissionChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: OnboardingStore,
) : PermissionChecker {

    override fun supportsNotifications(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    override fun grantState(permission: OnboardingPermission): GrantState {
        val granted = ContextCompat.checkSelfPermission(context, platformName(permission)) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return GrantState.Granted
        // Two denials is what Android takes before it stops showing its sheet, so a third request is a
        // silent "no" — which would look like a button that does nothing rather than a closed door.
        return if (store.askCount(permission.prefsKey) >= VisibleAskLimit) {
            GrantState.SettingsOnly
        } else {
            GrantState.Requestable
        }
    }

    override fun isBatteryExempt(): Boolean {
        // No power service means nothing to exempt, so the page answers "already done" rather than
        // offering a settings screen that does not exist here.
        val power = context.getSystemService(PowerManager::class.java) ?: return true
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    // The inlined name is a compile-time string, and it only ever reaches a launcher on a device that has the
    // permission: below 33 [supportsNotifications] keeps the notifications page out of the flow entirely.
    @Suppress("InlinedApi")
    override fun platformName(permission: OnboardingPermission): String = when (permission) {
        OnboardingPermission.Notifications -> Manifest.permission.POST_NOTIFICATIONS
        OnboardingPermission.Microphone -> Manifest.permission.RECORD_AUDIO
    }

    override fun recordAsk(permission: OnboardingPermission) = store.recordAsk(permission.prefsKey)

    private companion object {
        const val VisibleAskLimit = 2
    }
}
