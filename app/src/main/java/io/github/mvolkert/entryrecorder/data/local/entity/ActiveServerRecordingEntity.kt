package io.github.mvolkert.entryrecorder.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.mvolkert.entryrecorder.data.model.EventType

/**
 * One in-flight recording the server owns, persisted only so a process restart (reboot / swipe-away) can
 * resume reconciling and auto-stopping a job the server is still running. Transient by nature: a row lives
 * exactly as long as the app tracks the job and is deleted the moment tracking ends. Keyed by device id,
 * because the server runs at most one job per device.
 */
@Entity(tableName = "active_server_recordings")
data class ActiveServerRecordingEntity(
    @PrimaryKey val deviceId: Long,
    /** Server row id handed back from start (Phase S); null when the server returned none. */
    val recordingId: Long?,
    val eventType: EventType,
    val maxDurationSeconds: Int,
    /** Whether this client's request started the job, i.e. whether the app may auto-stop it. */
    val startedByThisRequest: Boolean,
    /** Wall-clock ms of the successful start; the reconcile deadline derives from this, not from "now". */
    val startedAtMs: Long
)
