package io.github.mvolkert.entryrecorder.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Local facade whose API surface mirrors the AndroidX Material 3 `MotionScheme` promoted in
 * `material3:1.5.0-alpha29` but declared `internal` in the currently resolved 1.4.0. Callers write
 * `MaterialTheme.appMotionScheme.<name>()` today; the one-line migration when AndroidX ships the
 * public API is to alias `appMotionScheme` to `MaterialTheme.motionScheme` and delete this file.
 */
interface AppMotionScheme {
    fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T>
    fun <T> fastSpatialSpec(): FiniteAnimationSpec<T>
    fun <T> slowSpatialSpec(): FiniteAnimationSpec<T>
    fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T>
    fun <T> fastEffectsSpec(): FiniteAnimationSpec<T>
    fun <T> slowEffectsSpec(): FiniteAnimationSpec<T>

    companion object {
        fun expressive(): AppMotionScheme = ExpressiveAppMotionScheme
        fun standard(): AppMotionScheme = StandardAppMotionScheme
    }
}

/**
 * Physics-preserved port of the retired `AppMotion` object: the previous nav-icon spring
 * (`DampingRatioMediumBouncy` + `StiffnessLow`) is exposed as `slowSpatialSpec`, so the sole live
 * call site in [io.github.mvolkert.entryrecorder.ui.Navigation] behaves bit-for-bit unchanged.
 */
private object ExpressiveAppMotionScheme : AppMotionScheme {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMedium,
    )

    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = 3000f,
    )

    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessLow,
    )

    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
    )

    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessHigh,
    )

    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessLow,
    )
}

/**
 * Utilitarian scheme kept as a name-compatible alternative: shorter, non-bouncy springs for spatial
 * bounds changes, and `tween` with M3 emphasized easings for effects (color, alpha). Approximates
 * AndroidX's `standard()` since the internal curves are not reachable from 1.4.0.
 */
private object StandardAppMotionScheme : AppMotionScheme {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
    )

    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessHigh,
    )

    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessLow,
    )

    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = tween(
        durationMillis = 200,
        easing = LinearOutSlowInEasing,
    )

    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = tween(
        durationMillis = 100,
        easing = LinearOutSlowInEasing,
    )

    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = tween(
        durationMillis = 400,
        easing = FastOutSlowInEasing,
    )
}

/**
 * Composition seam that lets the app reach the motion scheme from any `MaterialTheme` scope. M3
 * 1.4.0's `MaterialTheme(colorScheme = ...)` has no `motionScheme` parameter, so a `CompositionLocal`
 * is the only injection point available today.
 */
val LocalAppMotionScheme = staticCompositionLocalOf<AppMotionScheme> { ExpressiveAppMotionScheme }

/** Composable accessor that mirrors the future `MaterialTheme.motionScheme` extension. */
val MaterialTheme.appMotionScheme: AppMotionScheme
    @Composable get() = LocalAppMotionScheme.current
