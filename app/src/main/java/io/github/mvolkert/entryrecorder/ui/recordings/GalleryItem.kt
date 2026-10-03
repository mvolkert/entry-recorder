package io.github.mvolkert.entryrecorder.ui.recordings

import io.github.mvolkert.entryrecorder.data.local.entity.RecordingEntity
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.data.server.ServerRecordingDto

/**
 * One row in the merged Recordings gallery, covering both what the phone recorded itself ([Local], a Room
 * [RecordingEntity] whose file lives in this app's storage) and what the Python server recorded ([Remote],
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
    val durationSeconds: Long
    val sizeBytes: Long

    data class Local(val entity: RecordingEntity) : GalleryItem {
        override val stableKey: String get() = "local:${entity.id}"
        override val timestamp: Long get() = entity.timestamp
        override val deviceName: String get() = entity.deviceName
        override val eventType: EventType get() = entity.eventType
        override val durationSeconds: Long get() = entity.durationSeconds
        override val sizeBytes: Long get() = entity.fileSizeBytes
    }

    /**
     * A server recording with its thumbnail/video paths already resolved to absolute URLs (base + server-
     * relative path + `api_key`), so the UI can hand them straight to Coil / ExoPlayer without knowing the
     * configured server or key. [eventType] is parsed defensively: an unknown server label falls back to
     * MANUAL rather than dropping the row.
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
            get() = runCatching { EventType.valueOf(dto.eventType) }.getOrDefault(EventType.MANUAL)
        override val durationSeconds: Long get() = dto.durationSeconds.toLong()
        override val sizeBytes: Long get() = dto.fileSizeBytes
    }
}
