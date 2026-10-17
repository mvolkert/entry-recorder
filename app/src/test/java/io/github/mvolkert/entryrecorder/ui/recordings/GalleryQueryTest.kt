package io.github.mvolkert.entryrecorder.ui.recordings

import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.data.local.entity.triggerTypes
import io.github.mvolkert.entryrecorder.data.server.ServerRecordingDto
import io.github.mvolkert.entryrecorder.testing.assertEquals
import io.github.mvolkert.entryrecorder.testing.assertTrue
import io.github.mvolkert.entryrecorder.testing.testDevice
import io.github.mvolkert.entryrecorder.testing.testRecording
import org.junit.Test

/**
 * The gallery's match rules, tested without a ViewModel: the two awkward ones included (a clip that folded a
 * second trigger in matches either tag; a server label with no constant here maps to
 * [EventType.UNKNOWN] instead of guessing), the read-only no-video skip, and the rule that a local row and a
 * server row never borrow each other's id space.
 */
class GalleryQueryTest {
    private val noFilters = GalleryFilters(deviceId = null, eventType = null, query = "")

    private fun serverDto(
        id: Long = 5L,
        deviceId: Long = 77L,
        deviceName: String = "Doorbell",
        eventType: String = "MOTION",
        timestamp: Long = 1_700_000_000_000L,
        note: String? = null,
        videoUrl: String? = "/api/recordings/$id/video",
    ) = ServerRecordingDto(
        id = id,
        deviceId = deviceId,
        deviceName = deviceName,
        eventType = eventType,
        timestamp = timestamp,
        durationSeconds = 10,
        fileSizeBytes = 1_024L,
        isProtected = false,
        note = note,
        videoUrl = videoUrl,
        thumbnailUrl = "/api/recordings/$id/thumbnail",
    )

    @Test
    fun `a clip that folded a second trigger in matches either event-type filter`() {
        val recording = testRecording(id = 1, eventType = EventType.MOTION, filePath = "clip.mkv")
            .copy(alsoEventTypes = listOf(EventType.RING))
        assertTrue(EventType.RING in recording.triggerTypes, "the fixture must really carry both tags")

        val byRing = GalleryQuery.localRecordings(listOf(recording), noFilters.copy(eventType = EventType.RING))
        val byMotion = GalleryQuery.localRecordings(listOf(recording), noFilters.copy(eventType = EventType.MOTION))
        val byNoise = GalleryQuery.localRecordings(listOf(recording), noFilters.copy(eventType = EventType.NOISE))

        assertEquals(listOf(recording), byRing)
        assertEquals(listOf(recording), byMotion)
        assertTrue(byNoise.isEmpty(), "a tag the clip does not carry must not list it")
    }

    @Test
    fun `device and search filters narrow local rows`() {
        val gate = testRecording(id = 1, deviceId = 1L, deviceName = "Doorbell", filePath = "a.mkv")
        val garage = testRecording(id = 2, deviceId = 2L, deviceName = "Garage", filePath = "b.mkv")
            .copy(note = "birthday")
        val all = listOf(gate, garage)

        assertEquals(listOf(garage), GalleryQuery.localRecordings(all, noFilters.copy(deviceId = 2L)))
        assertEquals(listOf(garage), GalleryQuery.localRecordings(all, noFilters.copy(query = "GAR")))
        assertEquals(listOf(garage), GalleryQuery.localRecordings(all, noFilters.copy(query = "birth")))
        assertEquals(all, GalleryQuery.localRecordings(all, noFilters))
    }

    @Test
    fun `an unrecognised server event label matches the unknown filter rather than a guess`() {
        val dto = serverDto(eventType = "FLYING_SAUCER")

        assertEquals(
            listOf(dto.id),
            GalleryQuery.serverRows(listOf(dto), noFilters.copy(eventType = EventType.UNKNOWN), null).map { it.dto.id },
        )
        assertTrue(
            GalleryQuery.serverRows(listOf(dto), noFilters.copy(eventType = EventType.MOTION), null).isEmpty(),
            "an unknown label is shown honestly, not folded into MOTION",
        )
    }

    @Test
    fun `a server row without a video path never reaches the list`() {
        val rows = GalleryQuery.serverRows(listOf(serverDto(videoUrl = null)), noFilters, null)
        assertTrue(rows.isEmpty(), "a row that cannot be played is not listed")
    }

    @Test
    fun `the device filter matches a server row by the synced server id, else by name`() {
        val registered = testDevice(id = 1L, name = "Doorbell", serverDeviceId = 77L)
        val unregistered = testDevice(id = 1L, name = "Doorbell")
        val byServerId = GalleryQuery.serverRows(listOf(serverDto(deviceId = 77L)), noFilters.copy(deviceId = 1L), registered)
        val byName = GalleryQuery.serverRows(listOf(serverDto(deviceName = "Doorbell")), noFilters.copy(deviceId = 1L), unregistered)

        assertEquals(1, byServerId.size)
        assertEquals(1, byName.size)
    }

    @Test
    fun `a local device id never matches a server row that merely carries the same number`() {
        // The local device row has id 1 and is synced to server id 77; a server row stamped with device_id 1
        // belongs to some other device and must not turn up in this one's filtered view.
        val registered = testDevice(id = 1L, name = "Doorbell", serverDeviceId = 77L)
        val rows = GalleryQuery.serverRows(listOf(serverDto(deviceId = 1L)), noFilters.copy(deviceId = 1L), registered)

        assertTrue(rows.isEmpty(), "the two id spaces stay separate")
    }

    @Test
    fun `equal local and server ids stay two distinct rows`() {
        val local = testRecording(id = 5, filePath = "clip.mkv")
        val dto = serverDto(id = 5)

        val localItem = GalleryItem.Local(local)
        val remoteItem = GalleryItem.Remote(dto, thumbnailAbsoluteUrl = null, videoAbsoluteUrl = "http://host/video")

        assertEquals("local:5", localItem.stableKey)
        assertEquals("server:5", remoteItem.stableKey)
        assertTrue(localItem.stableKey != remoteItem.stableKey, "a merged list must not key both on the raw id")
    }
}
