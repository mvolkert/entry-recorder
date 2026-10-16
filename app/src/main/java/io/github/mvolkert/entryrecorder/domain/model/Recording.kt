package io.github.mvolkert.entryrecorder.domain.model

import io.github.mvolkert.entryrecorder.data.model.EventType

/**
 * A finalized local clip — the domain form of the stored recording row. [triggerTypes] is the whole
 * set of triggers the clip covers: the one it is filed under first, plus any that folded in while it
 * was still recording (a clip is never stopped to make room for a second trigger).
 */
data class Recording(
    val id: Long = 0,
    val deviceId: Long,
    val deviceName: String,
    val eventType: EventType,
    val alsoEventTypes: List<EventType> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val durationSeconds: Long = 0,
    val filePath: String,
    val fileSizeBytes: Long = 0,
    val thumbnailPath: String? = null,
    val isProtected: Boolean = false,
    val note: String? = null,
) {
    /** [eventType] first, then the folded-in triggers, deduplicated. */
    val triggerTypes: List<EventType> = buildList {
        add(eventType)
        alsoEventTypes.filterNot { it == eventType }.forEach { add(it) }
    }
}
