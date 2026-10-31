@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.mvolkert.entryrecorder.service

import io.github.mvolkert.entryrecorder.domain.model.TimeProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
    fun `a device-list reconcile does not cut an armed trigger hold short`() {
        val clipMs = 20L * 60L * 1000L
        reasons = emptySet()
        keeper.holdFor(clipMs)

        // The device rows re-emit while the clip runs and nothing in them says "hold": deciding this from the
        // reason set alone would drop the pair mid-clip for a trigger this phone cannot observe.
        keeper.reconcile()

        assertEquals(listOf("acquire($clipMs)", "acquire($clipMs)"), controller.calls)
        assertTrue(controller.held)
    }

    @Test
    fun `a short hold landing inside an armed long one does not pull the release forward`() = runTest(dispatcher) {
        val longMs = 20L * 60L * 1000L
        val shortMs = 6L * 60L * 1000L
        reasons = emptySet()

        keeper.holdFor(longMs)
        keeper.holdFor(shortMs)

        // The second clip is shorter, but the first deadline still stands: arming the safety net from the request
        // rather than from the surviving deadline would release the pair 14 minutes early.
        assertEquals(listOf("acquire($longMs)", "acquire($longMs)"), controller.calls)

        advanceBoth(shortMs)
        assertTrue(controller.held)

        advanceBoth(longMs - shortMs)
        assertFalse(controller.held)
        assertEquals("release", controller.calls.last())
    }

    /**
     * The guard the specs call "a trigger hold outlives its observable work": a server-side clip leaves this
     * phone with an empty reason set from the first tick, so only the armed [MonitorLockKeeper.holdFor] deadline
     * says the pair is still wanted. The clock moves with the scheduler here, otherwise the deadline check would
     * be inert and the assertion would pass for a keeper that simply never let go.
     */
    @Test
    fun `a tick keeps the pair for a trigger whose work this phone never observes`() = runTest(dispatcher) {
        val clipMs = 20L * 60L * 1000L
        val renewMs = MonitorLockKeeper.LOCK_RENEW_INTERVAL_MS
        reasons = emptySet()

        keeper.holdFor(clipMs)

        // The clip outlasts one window, so the first acquire is asked for the whole remaining hold.
        assertEquals(listOf("acquire($clipMs)"), controller.calls)

        advanceBoth(renewMs)
        // Half the hold is gone; the window shrinks to what is left of it rather than the standing timeout.
        assertEquals(
            listOf("acquire($clipMs)", "acquire(${clipMs - renewMs})"),
            controller.calls,
        )

        // Two more ticks still inside the hold, now floored at the standing window.
        advanceBoth(renewMs)
        advanceBoth(renewMs)
        assertEquals(
            listOf(
                "acquire($clipMs)",
                "acquire(${clipMs - renewMs})",
                "acquire(${MonitorLockKeeper.LOCK_HOLD_TIMEOUT_MS})",
                "acquire(${MonitorLockKeeper.LOCK_HOLD_TIMEOUT_MS})",
            ),
            controller.calls,
        )
        assertTrue(controller.held)

        // The armed deadline has passed and no reason ever appeared: the pair goes and nothing re-acquires.
        advanceBoth(renewMs)

        assertEquals("release", controller.calls.last())
        assertFalse(controller.held)
    }

    /**
     * Teardown has to be the last thing that touches the pair. The framework cannot be called here, so what is
     * proven is the keeper's own decision: it releases, hands back jobs it has cancelled, and refuses to take the
     * pair again for a callback that races the teardown.
     */
    @Test
    fun `shutdown releases the pair and hands back its cancelled jobs`() = runTest(dispatcher) {
        reasons = setOf(LockReason.MotionAnalyzerRunning)
        keeper.reconcile()

        val jobs = keeper.shutdown()

        assertEquals("release", controller.calls.last())
        assertFalse(controller.held)
        assertTrue(jobs.isNotEmpty())
        assertTrue(jobs.all { it.isCancelled })

        advanceBoth(MonitorLockKeeper.LOCK_RENEW_INTERVAL_MS)

        // Cancelled before the assertions for the same reason as the late-tick case below: a shutdown that failed
        // to cancel the renewal loop would keep refilling the scheduler instead of failing this test.
        val lastCall = controller.calls.last()
        val stillHeld = controller.held
        scope.cancel()

        assertEquals("release", lastCall)
        assertFalse(stillHeld)
    }

    @Test
    fun `a hold decision racing teardown does not take the pair again`() = runTest(dispatcher) {
        keeper.shutdown()
        // Teardown always records its unconditional release, so the guarantee is that nothing is appended after it.
        val callsAfterTeardown = controller.calls.toList()

        // The reason supplier still reports an analyzer and a clip still asks for cover: both arrive after the
        // release, and either one acquiring would leave the untimed Wi-Fi half held with nobody left to drop it.
        reasons = setOf(LockReason.MotionAnalyzerRunning)
        keeper.reconcile()
        keeper.holdFor(MonitorLockKeeper.LOCK_HOLD_TIMEOUT_MS)

        // The scope goes before the assertion, not after it: a keeper that acquired would also have started its
        // renewal loop, and runTest drains a scheduler that refills itself instead of reporting the failure.
        val afterRacingCallbacks = controller.calls.toList()
        val stillHeld = controller.held
        scope.cancel()

        assertEquals(callsAfterTeardown, afterRacingCallbacks)
        assertFalse(stillHeld)
    }

    /**
     * The one interleaving this class cannot avoid: the renewal job is cancelled by teardown, but a tick already
     * awake when the cancel landed still runs the decision afterwards. It re-acquires without going through
     * [MonitorLockKeeper.takeHold], so it starts no loop that could ever undo it — on the untimed radio half that
     * acquire would outlive the service that took it. Reached here by calling the tick directly, because a
     * cancelled job never fires again on a single-threaded test dispatcher.
     */
    @Test
    fun `a renewal tick already awake when teardown ran acquires nothing`() = runTest(dispatcher) {
        reasons = setOf(LockReason.MotionAnalyzerRunning)
        keeper.reconcile()

        keeper.shutdown()

        assertEquals("release", controller.calls.last())
        val callsAfterTeardown = controller.calls.toList()

        keeper.renewOrRelease()

        // Cancelled before the assertion for the same reason as the racing-callback case above: removing this
        // guard makes the tick acquire *and* leave nothing to stop, which would hang the run instead of failing it.
        val afterLateTick = controller.calls.toList()
        val stillHeld = controller.held
        scope.cancel()

        assertEquals(callsAfterTeardown, afterLateTick)
        assertFalse(stillHeld)
    }

    /** Moves the scheduler and the fake clock by the same step, so a tick reads the instant it really happens. */
    private fun TestScope.advanceBoth(stepMs: Long) {
        advanceTimeBy(stepMs)
        clock.nowMs += stepMs
        runCurrent()
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

    /**
     * A clock the tests drive by hand: the keeper computes its acquire windows from it, so time only moves here
     * when a test deliberately steps it alongside the scheduler.
     */
    private class FakeClock {
        var nowMs: Long = 0L
    }
}
