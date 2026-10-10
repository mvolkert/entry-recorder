package io.github.mvolkert.entryrecorder.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The app's single message channel. [AdaptiveScaffold] owns the instance and composes the visible
 * [androidx.compose.material3.SnackbarHost] (in the compact Scaffold's own slot, and bottom-aligned in
 * the rail layout), so every screen under the NavHost — tabs and settings details alike — posts into one
 * host that Material positions above the navigation bar and themes with the active scheme.
 *
 * A snack bar rather than a [android.widget.Toast]: toasts ignore the app theme and window insets, carry
 * no action, and the user can switch them off per app in system settings — which used to mean that the
 * report of a failed export or an unreachable server could simply never appear.
 */
val LocalAppSnackbar = staticCompositionLocalOf { SnackbarHostState() }

/** Bound on [showMessage]; see the note inside about a composition that renders no host. */
private const val DETACHED_HOST_GUARD_MS = 8_000L

/**
 * Show [text], briefly when [short]. Callers await it, so messages queue instead of overwriting each
 * other — a batch that reports three skipped protected clips plus a saved count delivers all three.
 */
suspend fun SnackbarHostState.showMessage(text: String, short: Boolean = false) {
    // showSnackbar suspends until a composed SnackbarHost has dismissed the message. A tree rendered
    // without AdaptiveScaffold (IncomingCallActivity installs its own content) has no host, so the wait
    // would never end and would stall the caller's one-shot event collector for good, silently dropping
    // every later message. Bounding it costs the visual on such a host and keeps the queue alive.
    val duration = if (short) SnackbarDuration.Short else SnackbarDuration.Long
    withTimeoutOrNull(DETACHED_HOST_GUARD_MS) { showSnackbar(message = text, duration = duration) }
}
