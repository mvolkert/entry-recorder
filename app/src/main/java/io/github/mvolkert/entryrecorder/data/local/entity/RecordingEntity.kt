package io.github.mvolkert.entryrecorder.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.mvolkert.entryrecorder.data.model.EventType

@Entity(
    tableName = "recordings",
    indices = [
        Index("timestamp"),
        Index("deviceId"),
        Index("eventType")
    ]
)
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val deviceId: Long,
    val deviceName: String,
    val eventType: EventType,
    /**
     * Triggers that happened during this clip but did not start it, as comma-separated enum names. A clip is
     * never stopped to make room for a second trigger, so a doorbell pressed during a motion recording has to
     * be able to say it covers both. Empty for every clip that only ever carried one trigger.
     */
    val alsoEventTypes: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val durationSeconds: Long = 0,
    val filePath: String,
    val fileSizeBytes: Long = 0,
    val thumbnailPath: String? = null,
    val isProtected: Boolean = false,
    val note: String? = null
)

/**
 * Every trigger this clip covers, the one it is filed under first: [RecordingEntity.eventType] followed by the
 * names in [RecordingEntity.alsoEventTypes]. Unreadable names are skipped rather than dropping the row.
 */
val RecordingEntity.triggerTypes: List<EventType>
    get() = buildList {
        add(eventType)
        alsoEventTypes.split(',').forEach { name ->
            runCatching { EventType.valueOf(name.trim()) }
                .getOrNull()
                ?.takeIf { it != eventType }
                ?.let { add(it) }
        }
    }
