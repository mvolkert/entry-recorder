package io.github.mvolkert.entryrecorder.video

import io.github.mvolkert.entryrecorder.data.model.MotionSensitivity
import io.github.mvolkert.entryrecorder.data.network.HttpSnapshotClient
import io.github.mvolkert.entryrecorder.domain.device.IntercomEvent
import io.github.mvolkert.entryrecorder.domain.device.IntercomEventListener
import io.github.mvolkert.entryrecorder.domain.model.TimeProvider
import io.github.mvolkert.entryrecorder.testing.testDevice
import kotlinx.coroutines.CoroutineDispatcher
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import kotlin.coroutines.CoroutineContext

/**
 * The analyzer's wiring, on the two clock readings it now distinguishes: an episode's durations come from elapsed
 * time, so a wall-clock jump cannot close it early or hold it open, while the pre-roll ring keeps the wall clock
 * because its stamp becomes the recorded clip's start time on disk. The health report is checked through the log it
 * produces, over the window it measured rather than the nominal minute.
 *
 * The JPEG fetch and decode stay outside this: `BitmapFactory` is a stub in JVM tests, so frames enter through
 * [OnDeviceMotionAnalyzer.evaluateFrame] and the loop is parked.
 */
class OnDeviceMotionAnalyzerTest {

    private companion object {
        const val FRAME_PIXELS = 96 * 54
        const val BLOCK = 200
    }

    /** Two of these side by side differ in [BLOCK] x 2 pixels, comfortably over the Balanced trigger bar. */
    private fun changedBlock(from: Int) = IntArray(FRAME_PIXELS) { if (it in from until from + BLOCK) 200 else 100 }

    private class FakeClock(
        var wallMs: Long = 1_700_000_000_000L,
        var elapsedMs: Long = 0L,
    ) : TimeProvider {
        override fun currentTimeMillis(): Long = wallMs
        override fun elapsedMillis(): Long = elapsedMs
    }

    private class RecordingListener : IntercomEventListener {
        val events = mutableListOf<IntercomEvent>()
        override fun onEvent(event: IntercomEvent) {
            events += event
        }
    }

    private class CapturingTree : Timber.Tree() {
        val lines = mutableListOf<String>()
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            lines += message
        }
    }

    /** Accepts the launch and never runs it, so nothing reaches the decode path this test cannot serve. */
    private object ParkingDispatcher : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = true
        override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
    }

    private val clock = FakeClock()
    private val listener = RecordingListener()
    private val tree = CapturingTree()
    private val device = testDevice(name = "EmuA").copy(motionSensitivity = MotionSensitivity.BALANCED)
    private lateinit var analyzer: OnDeviceMotionAnalyzer

    @Before
    fun plantTree() {
        Timber.plant(tree)
        analyzer = OnDeviceMotionAnalyzer(
            device = device,
            listener = listener,
            snapshotClient = HttpSnapshotClient(OkHttpClient(), ParkingDispatcher),
            ioDispatcher = ParkingDispatcher,
            timeProvider = clock,
        )
    }

    @After
    fun uprootTree() {
        Timber.uproot(tree)
    }

    private fun reports(): List<String> = tree.lines.filter { it.startsWith("Motion analysis on") }

    @Test
    fun `the health report spans the time it actually measured`() {
        clock.elapsedMs = 10_000L
        analyzer.start()

        clock.elapsedMs = 10_000L + 61_000L
        analyzer.reportHealthIfDue()
        assertEquals(1, reports().size)
        assertTrue(reports().last(), reports().last().contains("over 61.0s"))

        // Not due again until a full further window has gone by: the report re-arms from the moment it fired,
        // not from the nominal minute.
        analyzer.reportHealthIfDue()
        assertEquals(1, reports().size)
        clock.elapsedMs += 59_000L
        analyzer.reportHealthIfDue()
        assertEquals(1, reports().size)
        clock.elapsedMs += 1_000L
        analyzer.reportHealthIfDue()
        assertEquals(2, reports().size)
        assertTrue(reports().last(), reports().last().contains("over 60.0s"))
    }

    @Test
    fun `stop clears the ring and the next start re-seeds the window`() {
        analyzer.bufferPreRoll(byteArrayOf(1, 2, 3))
        analyzer.start()
        analyzer.stop()
        assertEquals(emptyList<PreRollFrame>(), analyzer.drainPreRoll())

        clock.elapsedMs = 500_000L
        analyzer.start()
        analyzer.reportHealthIfDue()
        assertEquals(0, reports().size)
        clock.elapsedMs += 60_000L
        analyzer.reportHealthIfDue()
        assertEquals(1, reports().size)
    }

    @Test
    fun `the pre-roll ring keeps the wall clock it will be written into the clip`() {
        clock.wallMs = 1_000L
        analyzer.bufferPreRoll(byteArrayOf(1))
        clock.wallMs = 900L
        analyzer.bufferPreRoll(byteArrayOf(2))
        assertEquals(listOf(1_000L, 900L), analyzer.drainPreRoll().map { it.timestampMs })
    }

    @Test
    fun `a wall clock jump mid episode neither closes it early nor keeps it open`() {
        clock.elapsedMs = 0L
        analyzer.evaluateFrame(changedBlock(0))
        clock.elapsedMs = 500L
        analyzer.evaluateFrame(changedBlock(400))
        clock.elapsedMs = 1_000L
        analyzer.evaluateFrame(changedBlock(800))

        assertTrue(analyzer.isEpisodeActive)
        assertEquals(1, listener.events.count { it is IntercomEvent.MotionOnDeviceStarted })
        val startLine = tree.lines.last()
        assertTrue(startLine, startLine.contains("Motion start on EmuA"))
        assertTrue(startLine, startLine.contains("over 2 frames in 0.5s"))

        // The user's clock goes an hour backwards while the scene is being watched.
        clock.wallMs -= 3_600_000L
        val quiet = changedBlock(800)
        clock.elapsedMs = 1_500L
        analyzer.evaluateFrame(quiet)
        clock.elapsedMs = 3_000L
        analyzer.evaluateFrame(quiet)
        assertTrue("1.5s of observed stillness must not close a 2s episode", analyzer.isEpisodeActive)
        assertEquals(0, listener.events.count { it is IntercomEvent.MotionOnDeviceEnded })

        clock.elapsedMs = 3_500L
        analyzer.evaluateFrame(quiet)
        assertFalse(analyzer.isEpisodeActive)
        assertEquals(1, listener.events.count { it is IntercomEvent.MotionOnDeviceEnded })
        val endLine = tree.lines.last()
        assertTrue(endLine, endLine.contains("after 2.0s still (3 frames)"))
    }
}
