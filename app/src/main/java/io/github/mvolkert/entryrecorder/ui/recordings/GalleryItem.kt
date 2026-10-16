package io.github.mvolkert.entryrecorder.ui.recordings

import io.github.mvolkert.entryrecorder.domain.model.Recording
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.data.server.ServerRecordingDto

/**
 * One row in the merged Recordings gallery, covering both what the phone recorded itself ([Local], a
 * domain [Recording] whose file lives in this app's storage) and what the Python server recorded ([Remote],
 * whose video and thumbnail live on the server and are reached over HTTP).
 *
 * The two sources keep their own id space — a local `id` 5 and a server `id` 5 are unrelated — so a merged
 * list keys rows on [stableKey] rather than the raw id, and the raw type stays reachable through the
 * concrete subclasses so callers can use whichever fields actually exist for that origin. Server rows are
 * surfaced read-only for now (browse + play); the local-only actions (export, delete, protect) live on
 * [Local].
 */
sealed interface GalleryItem {
    /** Composite LazyColumn key: never collides across the local and server id spaces. */
    val stableKey: String
    val timestamp: Long
    val deviceName: String
    val eventType: EventType
    /**
     * Every trigger this row's footage covers, the filed one first. A clip keeps recording when a second
     * trigger arrives, so one file can legitimately be both a motion and a ring recording.
     */
    val eventTypes: List<EventType>
    val durationSeconds: Long
    val sizeBytes: Long

    data class Local(val recording: Recording) : GalleryItem {
        override val stableKey: String get() = "local:${recording.id}"
        override val timestamp: Long get() = recording.timestamp
        override val deviceName: String get() = recording.deviceName
        override val eventType: EventType get() = recording.eventType
        override val eventTypes: List<EventType> get() = recording.triggerTypes
        override val durationSeconds: Long get() = recording.durationSeconds
        override val sizeBytes: Long get() = recording.fileSizeBytes
    }

    /**
     * A server recording with its thumbnail/video paths already resolved to absolute URLs (base + server-
     * relative path + `api_key`), so the UI can hand them straight to Coil / ExoPlayer without knowing the
     * configured server or key. [eventType] is parsed defensively: a label this app has no constant for falls
     * back to [EventType.UNKNOWN] rather than guessing MANUAL, so an unrecognised trigger is shown honestly;
     * the server's raw string stays on [dto] for inspection.
     */
    data class Remote(
        val dto: ServerRecordingDto,
        val thumbnailAbsoluteUrl: String?,
        val videoAbsoluteUrl: String
    ) : GalleryItem {
        override val stableKey: String get() = "server:${dto.id}"
        override val timestamp: Long get() = dto.timestamp
        override val deviceName: String get() = dto.deviceName
        override val eventType: EventType
            get() = runCatching { EventType.valueOf(dto.eventType) }.getOrDefault(EventType.UNKNOWN)
        override val eventTypes: List<EventType> get() = listOf(eventType)
        override val durationSeconds: Long get() = dto.durationSeconds.toLong()
        override val sizeBytes: Long get() = dto.fileSizeBytes
    }
}
