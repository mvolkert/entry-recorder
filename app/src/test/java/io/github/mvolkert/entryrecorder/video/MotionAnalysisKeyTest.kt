package io.github.mvolkert.entryrecorder.video

import io.github.mvolkert.entryrecorder.data.model.MotionSensitivity
import io.github.mvolkert.entryrecorder.domain.model.DeviceEndpointResolver
import io.github.mvolkert.entryrecorder.testing.testDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the monitor pool compares when it decides whether a running analyzer has to be rebuilt. The rebuild is
 * scoped to the fields the analysis actually reads, so an unrelated settings edit no longer throws away a running
 * episode's frame reference and pre-roll ring; the analyzed fields still have to take effect without a service
 * restart. Also proves the credential never reaches a log line through the key's own [toString].
 */
class MotionAnalysisKeyTest {

    private val baseline = testDevice()

    @Test
    fun `an edit the analyzer does not read keeps the key equal`() {
        listOf(
            baseline.copy(name = "Front gate"),
            baseline.copy(ringRecordSeconds = 120),
            baseline.copy(motionPostRecordSeconds = 45),
            baseline.copy(wakeOnMotion = false),
            baseline.copy(soundOnRing = false),
            baseline.copy(keepLocksHeld = true),
            baseline.copy(recordOnRing = false),
            baseline.copy(isEnabled = false),
            baseline.copy(snapshotFps = 2),
        ).forEach { edited ->
            assertEquals(
                "Editing $edited must not rebuild a running analyzer",
                OnDeviceMotionAnalyzer.keyFor(baseline),
                OnDeviceMotionAnalyzer.keyFor(edited),
            )
        }
    }

    @Test
    fun `each field the analyzer reads produces a different key`() {
        listOf(
            baseline.copy(id = 2L),
            baseline.copy(ipAddress = "192.168.1.60"),
            baseline.copy(useHttps = true),
            baseline.copy(snapshotPath = "/api/camera/other"),
            baseline.copy(username = "operator"),
            baseline.copy(password = "secret"),
            baseline.copy(motionSensitivity = MotionSensitivity.SENSITIVE),
            baseline.copy(motionPreRollFrames = 12),
        ).forEach { edited ->
            assertNotEquals(
                "Editing $edited changes what the analyzer polls or how it decides",
                OnDeviceMotionAnalyzer.keyFor(baseline),
                OnDeviceMotionAnalyzer.keyFor(edited),
            )
        }
    }

    @Test
    fun `a pre-roll edit clamped to the same frame count is not a change`() {
        // Both readings clamp to the ceiling, so comparing the stored value would rebuild the analyzer for an
        // identical ring size. The key holds the effective count for that reason.
        val ceiling = DeviceEndpointResolver.MOTION_PRE_ROLL_FRAMES_MAX
        assertEquals(
            OnDeviceMotionAnalyzer.keyFor(baseline.copy(motionPreRollFrames = ceiling)),
            OnDeviceMotionAnalyzer.keyFor(baseline.copy(motionPreRollFrames = ceiling + 5)),
        )
    }

    @Test
    fun `the key form keeps the snapshot password out of the log`() {
        val key = OnDeviceMotionAnalyzer.keyFor(baseline.copy(password = "supers3cret"))

        val text = key.toString()
        assertFalse(text, text.contains("supers3cret"))
        // The rest of the identity stays readable, otherwise a rebuild warning says nothing.
        assertTrue(text, text.contains(baseline.snapshotUrl))
    }
}
