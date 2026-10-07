package io.github.mvolkert.entryrecorder.ui.adaptive

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp

/**
 * The app's window-size bucket. Deliberately narrower than the upstream M3 [WindowWidthSizeClass] so
 * a future swap to `androidx.window` `currentWindowAdaptiveInfo()` (once a foldable is available for
 * testing) stays a one-file edit here instead of rippling through every adaptive call site.
 */
@Immutable
enum class WindowInfo { Compact, Medium, Expanded }

/** Rail layout on Medium and Expanded; bottom bar on Compact. */
val WindowInfo.useRail: Boolean get() = this != WindowInfo.Compact

/** Provided once by AppRoot so descendants can pick a max content width without prop drilling. */
val LocalWindowInfo: ProvidableCompositionLocal<WindowInfo> = compositionLocalOf { WindowInfo.Compact }

/** The current window bucket, recomputed when the owning Activity's size class changes. */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun rememberWindowInfo(): WindowInfo {
    if (LocalInspectionMode.current) return WindowInfo.Compact
    val activity = LocalContext.current.findActivity() ?: return WindowInfo.Compact
    val sizeClass = calculateWindowSizeClass(activity)
    return remember(sizeClass) { sizeClass.toWindowInfo() }
}

private fun WindowSizeClass.toWindowInfo(): WindowInfo = when (widthSizeClass) {
    WindowWidthSizeClass.Compact -> WindowInfo.Compact
    WindowWidthSizeClass.Medium -> WindowInfo.Medium
    else -> WindowInfo.Expanded
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Centers the child at up to 840 dp on Expanded; a no-op on Compact and Medium where there is
 * nothing to cap. Reads [LocalWindowInfo] so call sites stay terse.
 */
@Composable
fun Modifier.appContentMaxWidth(): Modifier {
    val info = LocalWindowInfo.current
    return if (info == WindowInfo.Expanded) this.widthIn(max = 840.dp) else this
}
