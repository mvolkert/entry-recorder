package io.github.mvolkert.entryrecorder.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.mvolkert.entryrecorder.ui.adaptive.appContentMaxWidth
import io.github.mvolkert.entryrecorder.ui.theme.Spacing
import io.github.mvolkert.entryrecorder.ui.theme.rememberExpressiveMotionEnabled

/**
 * The first-run flow's single action slot, pinned to the bottom of the window and composed *outside* the
 * pager so the filled button never moves, resizes or changes variant between pages: one [Button], always
 * `fillMaxWidth()` under [appContentMaxWidth], sized by the component itself rather than a hand-set
 * height (Design.md §1). Only its label changes, and only that label animates.
 *
 * The [secondary] slot is always composed as well — every page has a way to decline or step past the ask —
 * because a slot that appeared only on some pages would shift the filled button's y-position, which is the
 * one thing this bar exists to prevent.
 */
@Composable
fun OnboardingPermissionBar(
    primary: OnboardingAction,
    secondary: OnboardingAction,
    onPrimaryClick: () -> Unit,
    onSecondaryClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val animateLabels = rememberExpressiveMotionEnabled()
    Column(
        modifier = modifier
            .appContentMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Button(
            onClick = onPrimaryClick,
            enabled = primary.enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ActionLabel(primary, animateLabels)
        }
        TextButton(
            onClick = onSecondaryClick,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ActionLabel(secondary, animateLabels)
        }
    }
}

/**
 * The label inside one of the two slots, with the satisfied-ask check in front of it. The container is
 * always full width and centered, so a short label on one page and a long one on the next draw in the same
 * box instead of nudging the button's contents.
 *
 * [animateLabels] comes from the caller because it is the animator-duration-scale gate: with animation off
 * the swap has to be instant rather than stuck mid-crossfade.
 */
@Composable
private fun ActionLabel(action: OnboardingAction, animateLabels: Boolean) {
    val label: @Composable (Int) -> Unit = { labelRes ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(
                space = ButtonDefaults.IconSpacing,
                alignment = Alignment.CenterHorizontally,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (action.completed) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
            }
            Text(stringResource(labelRes))
        }
    }
    if (!animateLabels) {
        label(action.labelRes)
        return
    }
    // AnimatedContent's transitionSpec is not a composable scope, so resolve the spec before it.
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    AnimatedContent(
        targetState = action.labelRes,
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
        transitionSpec = {
            fadeIn(animationSpec = effects) togetherWith fadeOut(animationSpec = effects)
        },
        label = "onboardingActionLabel",
    ) { labelRes ->
        label(labelRes)
    }
}
