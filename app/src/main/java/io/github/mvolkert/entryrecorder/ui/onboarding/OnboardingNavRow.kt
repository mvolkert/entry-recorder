package io.github.mvolkert.entryrecorder.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.mvolkert.entryrecorder.ui.adaptive.appContentMaxWidth
import io.github.mvolkert.entryrecorder.ui.theme.Spacing

/**
 * The pager's Previous | Next row, pinned under [OnboardingPermissionBar] as the bottom-most thing on the
 * screen — which is why the navigation-bar inset lives here rather than on the bar above it.
 *
 * It is the flow's positional navigation and reads nothing but the page index: every grant state — denied,
 * denied twice, exempt or allowed — leaves both slots exactly as they were, so a permission the user did
 * not grant cannot park them on a page.
 *
 * Both slots are always composed and each takes half the row. The ends of the flow disable their own button
 * rather than dropping it, because a row that changed height or split its width between pages would move
 * the filled action above it — the one control this screen promises not to move.
 */
@Composable
fun OnboardingNavRow(
    previous: OnboardingAction,
    next: OnboardingAction,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .appContentMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        TextButton(
            onClick = onPreviousClick,
            enabled = previous.enabled,
            modifier = Modifier.weight(1f),
        ) {
            NavSlot(previous, glyphAtStart = true)
        }
        TextButton(
            onClick = onNextClick,
            enabled = next.enabled,
            modifier = Modifier.weight(1f),
        ) {
            NavSlot(next, glyphAtStart = false)
        }
    }
}

/**
 * One slot's contents: the direction glyph on the outside edge, the label inside it, mirrored between the
 * two halves so the row reads as the two ways the pager moves. The glyph carries no content description —
 * the label is the accessible name, and a second one saying "arrow back" would only compete with it.
 */
@Composable
private fun NavSlot(action: OnboardingAction, glyphAtStart: Boolean) {
    val glyph: @Composable () -> Unit = {
        Icon(
            imageVector = if (glyphAtStart) {
                Icons.AutoMirrored.Filled.ArrowBack
            } else {
                Icons.AutoMirrored.Filled.ArrowForward
            },
            contentDescription = null,
        )
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(
            space = ButtonDefaults.IconSpacing,
            alignment = Alignment.CenterHorizontally,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyphAtStart) glyph()
        Text(stringResource(action.labelRes))
        if (!glyphAtStart) glyph()
    }
}
