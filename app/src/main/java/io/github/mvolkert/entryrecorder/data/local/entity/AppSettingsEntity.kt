package io.github.mvolkert.entryrecorder.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.mvolkert.entryrecorder.data.model.RecordingMode

@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey
    val id: Int = 1,
    val recordingMode: RecordingMode = RecordingMode.APP_LOCAL,
    val serverBaseUrl: String = "http://192.168.1.100:8000",
    val serverApiKey: String = "",
    val retentionDays: Int = 14,             // Auto-delete recordings older than N days (0 = disabled)
    val maxStorageUsageMb: Long = 10240,     // 10 GB limit before purging oldest unprotected recordings
    val autoCleanupEnabled: Boolean = true,
    val wakeOnMotion: Boolean = true,
    val wakeOnRing: Boolean = true,
    val wakeOnNoise: Boolean = true,
    val vibrateOnRing: Boolean = true,
    val soundOnRing: Boolean = true,
    // When true, exporting/sharing a recording transcodes its MJPEG MKV to H.264 first so it plays
    // in other apps (costs CPU/battery only at export time). When false, the raw MKV is shared.
    val transcodeOnExport: Boolean = true,
    // SAF tree URI (ACTION_OPEN_DOCUMENT_TREE, persisted permission) used both by "Export to folder"
    // (one file per recording) and by the auto-export mirror below. Empty = no folder chosen yet.
    val exportFolderUri: String = "",
    // Opt-in archive mirror: copies the original (lossless) MJPEG MKV into exportFolderUri right
    // after a local recording is finalized, so sync tools always see complete, up-to-date files.
    val autoExportOnFinalize: Boolean = false
)
