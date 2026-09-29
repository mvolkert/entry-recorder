package io.github.mvolkert.entryrecorder.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

/**
 * Centralized Material 3 Expressive motion tokens. All animation in the app reads from these spring
 * specs rather than fixed durations (a spring's runtime is derived from its physics, so this also
 * satisfies the "no custom durations" convention).
 */
object AppMotion {
    /** Snappy spring for spatial changes — pager page scale/position. */
    val spatial: SpringSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** Bouncier spring for expressive emphasis, e.g. the selected nav-bar icon. */
    val emphasis: SpringSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessLow,
    )

    /** Smooth, non-bouncy spring for opacity/content fades. */
    val content: SpringSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
    )

    // Pager page-transition constants (expressive scale + fade of the off-center page).
    const val PAGE_MIN_SCALE = 0.92f
    const val PAGE_MIN_ALPHA = 0.55f
}
