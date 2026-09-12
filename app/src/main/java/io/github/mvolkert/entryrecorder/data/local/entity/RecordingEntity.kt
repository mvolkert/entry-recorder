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
    val timestamp: Long = System.currentTimeMillis(),
    val durationSeconds: Long = 0,
    val filePath: String,
    val fileSizeBytes: Long = 0,
    val thumbnailPath: String? = null,
    val isProtected: Boolean = false,
    val note: String? = null
)
