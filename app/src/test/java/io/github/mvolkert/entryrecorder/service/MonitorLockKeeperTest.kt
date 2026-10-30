@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.mvolkert.entryrecorder.service

import io.github.mvolkert.entryrecorder.domain.model.TimeProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hold policy with the Android lock mechanics replaced by a recording fake. What is proven here is the part
 * that used to be untestable: that a polling analyzer alone is enough to take the pair, that the last reason
 * going away releases it, and that renewing a bounded acquire never leaves a window where nothing is held. The
 * framework calls themselves are the device gate.
 */
class MonitorLockKeeperTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val controller = FakeLockController()
    private val clock = FakeClock()
    private var reasons = emptySet<LockReason>()

    private val keeper = MonitorLockKeeper(
        controller = controller,
        scope = scope,
        timeProvider = TimeProvider { clock.nowMs },
        reasons = { reasons },
    )

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `a running analyzer alone takes the pair for one bounded window`() {
        reasons = setOf(LockReason.MotionAnalyzerRunning)

        keeper.reconcile()

        assertEquals(listOf("acquire(${MonitorLockKeeper.LOCK_HOLD_TIMEOUT_MS})"), controller.calls)
        assertTrue(controller.held)
    }

    @Test
    fun `a deployment with no analyzer and no idle hold takes nothing`() {
        reasons = LockHoldPolicy.reasonsFor(
            pendingPostRecordStop = false,
            localRecording = false,
            keepLocksHeldDevice = false,
            motionAnalyzerRunning = false,
        )

        keeper.reconcile()

        // The camera-event-only setup must stay exactly as battery-cheap as before this change.
        assertEquals(emptyList<String>(), controller.calls)
        assertFalse(controller.held)
    }

    @Test
    fun `the last reason releasing drops both locks`() {
        reasons = setOf(LockReason.LocalRecording, LockReason.MotionAnalyzerRunning)
        keeper.reconcile()
        reasons = emptySet()

        keeper.releaseIfIdle()

        assertEquals("acquire(${MonitorLockKeeper.LOCK_HOLD_TIMEOUT_MS})", controller.calls.first())
        assertEquals("release", controller.calls.last())
        assertFalse(controller.held)
    }

    @Test
    fun `renewal re-acquires while a reason holds without a release in between`() = runTest(dispatcher) {
        reasons = setOf(LockReason.MotionAnalyzerRunning)
        keeper.reconcile()

        advanceTimeBy(MonitorLockKeeper.LOCK_RENEW_INTERVAL_MS)
        runCurrent()

        assertEquals(
            listOf(
                "acquire(${MonitorLockKeeper.LOCK_HOLD_TIMEOUT_MS})",
                "acquire(${MonitorLockKeeper.LOCK_HOLD_TIMEOUT_MS})",
            ),
            controller.calls,
        )
        assertTrue(controller.held)

        // The reason still holds, so the renewal loop is alive: end it before the test body returns, or runTest
        // keeps advancing virtual time to drain a scheduler that refills itself.
        scope.cancel()
    }

    @Test
    fun `renewal stops once the reason goes away`() = runTest(dispatcher) {
        reasons = setOf(LockReason.MotionAnalyzerRunning)
        keeper.reconcile()

        // The device that was analyzing is switched away from in-app analysis, and nothing else needs the pair.
        reasons = emptySet()
        advanceTimeBy(MonitorLockKeeper.LOCK_RENEW_INTERVAL_MS)
        runCurrent()

        assertEquals(listOf("acquire(${MonitorLockKeeper.LOCK_HOLD_TIMEOUT_MS})", "release"), controller.calls)

        advanceTimeBy(MonitorLockKeeper.LOCK_RENEW_INTERVAL_MS)
        runCurrent()

        assertEquals(2, controller.calls.size)
        assertFalse(controller.held)
    }

    @Test
    fun `a trigger hold longer than one window keeps its own deadline and is not cut short`() = runTest(dispatcher) {
        val clipMs = 20L * 60L * 1000L
        reasons = emptySet()

        keeper.holdFor(clipMs)

        // The armed clip outlasts the standing window, so the first acquire covers it entirely.
        assertEquals(listOf("acquire($clipMs)"), controller.calls)

        advanceTimeBy(MonitorLockKeeper.LOCK_RENEW_INTERVAL_MS)
        runCurrent()
        assertTrue(controller.held)

        // The recorder ends the clip on its own deadline; the service never sees that stop.
        advanceTimeBy(clipMs)
        runCurrent()

        assertFalse(controller.held)
        assertEquals("release", controller.calls.last())
    }

    /** The pair as the keeper sees it: every call recorded in order, with the held state it left behind. */
    private class FakeLockController : LockController {
        val calls = mutableListOf<String>()
        var held = false
            private set

        override fun acquire(timeoutMs: Long) {
            calls += "acquire($timeoutMs)"
            held = true
        }

        override fun release() {
            calls += "release"
            held = false
        }

        override fun isHeld(): Boolean = held
    }

    /** A clock that stays put: these tests assert the acquire windows the keeper asks for, not elapsed time. */
    private class FakeClock {
        var nowMs: Long = 0L
    }
}
