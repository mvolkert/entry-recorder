package io.github.mvolkert.entryrecorder.ui.recordings

import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.data.server.ServerRecordingDto
import io.github.mvolkert.entryrecorder.domain.model.Device
import io.github.mvolkert.entryrecorder.domain.model.Recording

/** The three filters the gallery narrows by: a device, an event type, and the search [query]. */
data class GalleryFilters(val deviceId: Long?, val eventType: EventType?, val query: String) {
    /** True when at least one of the three is narrowing the list, which changes the empty state. */
    val active: Boolean get() = deviceId != null || eventType != null || query.isNotBlank()
}

/**
 * A server row that passed the filters, with the video path the row cannot be built without already resolved.
 *
 * Skipping a row with no video is part of matching, so [GalleryQuery] returns this pair rather than a bare dto
 * the caller would have to re-check.
 */
data class ServerRow(val dto: ServerRecordingDto, val videoUrl: String)

/**
 * Which archive rows the current filters let through, for both origins.
 *
 * Lifted verbatim out of [RecordingsViewModel]'s `uiState` combine so the match rules — including the two
 * awkward ones (a clip that folded a second trigger in matches either tag; an unrecognised server label maps to
 * [EventType.UNKNOWN] rather than guessing) — can be exercised from an ordinary JVM test.
 *
 * Building the `GalleryItem` wrapper, and the absolute thumbnail/video URLs a remote row needs, stays with the
 * ViewModel: that mapping consumes the configured server base url and api key, and keeping it a one-liner there
 * keeps it obviously correct.
 */
object GalleryQuery {
    /** Local clips in archive order of the given list, narrowed by [filters]. */
    fun localRecordings(recordings: List<Recording>, filters: GalleryFilters): List<Recording> =
        recordings.filter { recording ->
            val matchesDevice = filters.deviceId == null || recording.deviceId == filters.deviceId
            // A clip that folded a second trigger in while it was still recording matches either tag, so a
            // doorbell pressed during a motion recording turns up under Ring as well as under Motion.
            val typeFilter = filters.eventType
            val matchesType = typeFilter == null || typeFilter in recording.triggerTypes
            val matchesQuery = filters.query.isBlank() ||
                recording.deviceName.contains(filters.query, ignoreCase = true) ||
                (recording.note?.contains(filters.query, ignoreCase = true) == true)
            matchesDevice && matchesType && matchesQuery
        }

    /**
     * Server rows a listing answer lets through, in listing order.
     *
     * Mirrors the local rules, except the device filter matches server rows by the server-assigned id the Phase S
     * sync stored, falling back to a name compare for a device not yet registered. [selectedDevice] is the device
     * the filter names, or null when no device filter is set.
     */
    fun serverRows(dtos: List<ServerRecordingDto>, filters: GalleryFilters, selectedDevice: Device?): List<ServerRow> =
        dtos.mapNotNull { dto ->
            val serverId = selectedDevice?.serverDeviceId
            val matchesDevice = filters.deviceId == null ||
                if (serverId != null) dto.deviceId == serverId
                else dto.deviceName == selectedDevice?.name
            val matchesType = filters.eventType == null ||
                runCatching { EventType.valueOf(dto.eventType) }.getOrDefault(EventType.UNKNOWN) == filters.eventType
            val matchesQuery = filters.query.isBlank() ||
                dto.deviceName.contains(filters.query, ignoreCase = true) ||
                (dto.note?.contains(filters.query, ignoreCase = true) == true)
            val video = dto.videoUrl
            if (!matchesDevice || !matchesType || !matchesQuery || video == null) null
            else ServerRow(dto = dto, videoUrl = video)
        }
}
