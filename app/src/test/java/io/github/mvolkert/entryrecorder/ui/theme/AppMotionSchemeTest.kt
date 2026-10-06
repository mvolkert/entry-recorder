package io.github.mvolkert.entryrecorder.ui.theme

import androidx.compose.ui.unit.Dp
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Locks the seam's shape to the future AndroidX `MotionScheme` API: both factories return a non-null
 * scheme, every spec method resolves for the types the app actually animates today, and the two
 * built-in schemes are stable singletons so the CompositionLocal never churns on recomposition.
 * If AndroidX renames a spec, the corresponding override here stops compiling — which is the signal
 * to migrate the facade to the real type.
 */
class AppMotionSchemeTest {

    @Test
    fun `expressive and standard factories return singletons`() {
        assertSame(AppMotionScheme.expressive(), AppMotionScheme.expressive())
        assertSame(AppMotionScheme.standard(), AppMotionScheme.standard())
        assertNotNull(AppMotionScheme.expressive())
        assertNotNull(AppMotionScheme.standard())
    }

    @Test
    fun `expressive scheme exposes all six spec slots for Float`() = assertAllSpecs(AppMotionScheme.expressive())

    @Test
    fun `standard scheme exposes all six spec slots for Float`() = assertAllSpecs(AppMotionScheme.standard())

    @Test
    fun `spec slots also resolve for Dp and Boolean`() {
        for (scheme in listOf(AppMotionScheme.expressive(), AppMotionScheme.standard())) {
            assertNotNull(scheme.defaultSpatialSpec<Dp>())
            assertNotNull(scheme.fastSpatialSpec<Dp>())
            assertNotNull(scheme.slowSpatialSpec<Dp>())
            assertNotNull(scheme.defaultEffectsSpec<Dp>())
            assertNotNull(scheme.fastEffectsSpec<Dp>())
            assertNotNull(scheme.slowEffectsSpec<Dp>())
            assertNotNull(scheme.defaultEffectsSpec<Boolean>())
            assertNotNull(scheme.slowEffectsSpec<Boolean>())
        }
    }

    private fun assertAllSpecs(scheme: AppMotionScheme) {
        assertNotNull(scheme.defaultSpatialSpec<Float>())
        assertNotNull(scheme.fastSpatialSpec<Float>())
        assertNotNull(scheme.slowSpatialSpec<Float>())
        assertNotNull(scheme.defaultEffectsSpec<Float>())
        assertNotNull(scheme.fastEffectsSpec<Float>())
        assertNotNull(scheme.slowEffectsSpec<Float>())
    }
}
