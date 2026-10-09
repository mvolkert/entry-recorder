package io.github.mvolkert.entryrecorder.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mvolkert.entryrecorder.ui.theme.Spacing
import io.github.mvolkert.entryrecorder.ui.theme.rememberExpressiveMotionEnabled
import kotlinx.coroutines.delay

/** How long the check holds before it dismisses itself; the spring needs the full beat to settle. */
private const val CELEBRATION_VISIBLE_MS = 1_500L

/** Geometry of the morph: the badge grows out of the control it replaces rather than popping in. */
private const val ENTER_SCALE = 0.7f

/**
 * The app's single celebration moment: a check that springs in over a finished flow, holds briefly and
 * dismisses itself, paired with a [HapticFeedbackType.Confirm] so the completion is felt as well as seen.
 * Callers own the visible flag and clear it in [onDismiss]; the exit animation runs on its own.
 *
 * Two forms, both from one composable so no screen invents its own flourish: with a [message] it is a
 * tonal pill (badge + label, contrast-checked container/on pair) for inline placement; without a label
 * ([showMessage] = false) it is the bare [MaterialShapes] badge that sits exactly on top of a small FAB.
 * The message is then read out as the icon's description instead, so the moment is never visual-only.
 *
 * With the system animation scale at zero there is no celebration at all (Design.md §3): the flag is
 * handed straight back so the caller is not left waiting on a moment that will never be shown.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CelebrationCheck(
    visible: Boolean,
    onDismiss: () -> Unit,
    containerColor: Color,
    contentColor: Color,
    message: String,
    modifier: Modifier = Modifier,
    showMessage: Boolean = true,
    size: Dp = 40.dp,
    iconSize: Dp = 22.dp,
) {
    if (!rememberExpressiveMotionEnabled()) {
        LaunchedEffect(visible) { if (visible) onDismiss() }
        return
    }

    val haptic = LocalHapticFeedback.current
    val spatialSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val effectsSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()

    LaunchedEffect(visible) {
        if (visible) {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            delay(CELEBRATION_VISIBLE_MS)
            onDismiss()
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = scaleIn(animationSpec = spatialSpec, initialScale = ENTER_SCALE) +
            fadeIn(animationSpec = effectsSpec),
        exit = scaleOut(animationSpec = spatialSpec, targetScale = ENTER_SCALE) +
            fadeOut(animationSpec = effectsSpec),
        modifier = modifier,
        label = "celebrationCheck",
    ) {
        if (showMessage) {
            Surface(
                shape = CircleShape, // stadium pill, same reading as StatusChip
                color = containerColor,
                contentColor = contentColor,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CelebrationBadge(
                        containerColor = containerColor,
                        contentColor = contentColor,
                        size = size,
                        iconSize = iconSize,
                        description = null,
                    )
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    Text(text = message, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
        } else {
            CelebrationBadge(
                containerColor = containerColor,
                contentColor = contentColor,
                size = size,
                iconSize = iconSize,
                description = message,
            )
        }
    }
}

/** The check inside the app's one decorative shape, tinted by the caller's contrast-checked pair. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CelebrationBadge(
    containerColor: Color,
    contentColor: Color,
    size: Dp,
    iconSize: Dp,
    description: String?,
) {
    Box(
        modifier = Modifier
            .size(size)
            .background(containerColor, MaterialShapes.Cookie9Sided.toShape()),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = description,
            modifier = Modifier.size(iconSize),
            tint = contentColor,
        )
    }
}
