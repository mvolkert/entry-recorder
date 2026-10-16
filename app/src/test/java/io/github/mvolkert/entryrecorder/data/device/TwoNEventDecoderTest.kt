package io.github.mvolkert.entryrecorder.data.device

import com.google.gson.JsonParser
import io.github.mvolkert.entryrecorder.domain.device.IntercomEvent
import io.github.mvolkert.entryrecorder.domain.model.Device
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 2.1 of `add-2n-logging-api-transport`: the Logging API drain hands each log entry to the shared
 * decode, and ring / motion / noise must land on the existing [IntercomEvent] types.
 *
 * The decode is a pure function of a parsed JSON body, so these tests drive [TwoNEventDecoder] straight
 * from 2N-shaped payloads — no device, no network, no clock. They pin the contract that lets the transport
 * be swapped under [TwoNIPVersoDevice] without changing what the monitor service consumes.
 */
class TwoNEventDecoderTest {

    private val device = Device(id = 3L, name = "Gate", ipAddress = "192.168.1.9")
    private val tag = "test"

    private fun decode(json: String): List<IntercomEvent> =
        TwoNEventDecoder.decodeAll(device, JsonParser.parseString(json).asJsonObject, tag)

    @Test
    fun `motion active edge maps to MotionStarted`() {
        val event = decode("""{"event":"MotionDetected","params":{"state":"active"}}""").single()
        assertTrue(event is IntercomEvent.MotionStarted)
        assertEquals(device, event.device)
    }

    @Test
    fun `motion inactive edge maps to MotionEnded`() {
        val events = decode("""{"event":"MotionDetected","params":{"state":"inactive"}}""")
        assertTrue(events.single() is IntercomEvent.MotionEnded)
    }

    @Test
    fun `motion accepts a real boolean and a number for the state field`() {
        assertTrue(decode("""{"event":"MotionDetected","params":{"state":true}}""").single() is IntercomEvent.MotionStarted)
        assertTrue(decode("""{"event":"MotionDetected","params":{"state":0}}""").single() is IntercomEvent.MotionEnded)
    }

    @Test
    fun `motion without a usable state maps to nothing rather than a fabricated end`() {
        assertTrue(decode("""{"event":"MotionDetected","params":{"state":"banana"}}""").isEmpty())
        assertTrue(decode("""{"event":"MotionDetected","params":{}}""").isEmpty())
    }

    @Test
    fun `noise edges map to the NoiseStarted and NoiseEnded events`() {
        assertTrue(decode("""{"event":"NoiseDetected","params":{"active":"on"}}""").single() is IntercomEvent.NoiseStarted)
        assertTrue(decode("""{"event":"NoiseDetected","params":{"active":"off"}}""").single() is IntercomEvent.NoiseEnded)
    }

    @Test
    fun `a doorbell key press rings with the button caller and a release is ignored`() {
        val press = decode("""{"event":"KeyPressed","params":{"action":"pressed"}}""").single() as IntercomEvent.DoorbellRung
        assertEquals("Doorbell Button", press.callerNumber)

        // An undocumented press spelling still rings — only the known release/hold edges are dropped.
        assertTrue(decode("""{"event":"KeyPressed","params":{}}""").single() is IntercomEvent.DoorbellRung)
        assertTrue(decode("""{"event":"KeyPressed","params":{"action":"released"}}""").isEmpty())
        assertTrue(decode("""{"event":"KeyPressed","params":{"action":"long"}}""").isEmpty())
    }

    @Test
    fun `a ringing call state rings with the peer number and an established call does not`() {
        val ring = decode("""{"event":"CallStateChanged","params":{"state":"ringing","peer":"+4912345"}}""")
            .single() as IntercomEvent.DoorbellRung
        assertEquals("+4912345", ring.callerNumber)

        assertTrue(decode("""{"event":"CallStateChanged","params":{"state":"dialing"}}""").single() is IntercomEvent.DoorbellRung)
        assertTrue(decode("""{"event":"CallStateChanged","params":{"state":"connected"}}""").isEmpty())
    }

    @Test
    fun `an array drained under the result envelope fans out to one event per entry`() {
        val events = decode(
            """
            {"result":{"log":[
              {"event":"MotionDetected","params":{"state":"active"}},
              {"event":"KeyPressed","params":{"action":"pressed"}},
              {"event":"CallStateChanged","params":{"state":"idle"}}
            ]}}
            """.trimIndent(),
        )
        assertEquals(2, events.size)
        assertTrue(events[0] is IntercomEvent.MotionStarted)
        assertTrue(events[1] is IntercomEvent.DoorbellRung)
    }

    @Test
    fun `a single bare event object at the root still decodes`() {
        assertTrue(decode("""{"type":"NoiseDetected","params":{"state":"active"}}""").single() is IntercomEvent.NoiseStarted)
    }

    @Test
    fun `an unrecognised envelope decodes to nothing`() {
        assertTrue(decode("""{"result":{"id":"ch-1"}}""").isEmpty())
    }
}
