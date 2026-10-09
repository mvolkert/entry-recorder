package io.github.mvolkert.entryrecorder.ui.theme

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Whether the system lets the app animate at all — the developer-option animator duration scale,
 * read once per composition host. Design.md §3 requires the expressive tiers to fall back to flat
 * progress, crossfades and no celebration when this is `false`, so every shared-element, wavy and
 * celebration call site gates on it through this one function instead of touching [Settings] itself.
 * A missing key means the default (1) and therefore animation on.
 */
@Composable
fun rememberExpressiveMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
}
