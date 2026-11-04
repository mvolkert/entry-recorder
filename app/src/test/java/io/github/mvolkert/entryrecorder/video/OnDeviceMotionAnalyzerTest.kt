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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import kotlin.coroutines.CoroutineContext

/**
 * The analyzer's wiring, on the two clock readings it now distinguishes: the health window is measured from elapsed
 * time, while the pre-roll ring keeps the wall clock because its stamp becomes the recorded clip's start time on
 * disk. The health report is checked through the log it produces, over the window it measured rather than the nominal
 * minute.
 *
 * The JPEG fetch and decode stay outside this: `BitmapFactory` is a stub in JVM tests, so the loop is parked.
 */
class OnDeviceMotionAnalyzerTest {

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
}
