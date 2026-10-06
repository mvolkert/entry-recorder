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
    // Legacy: alert & lockscreen behavior is now configured per device (see DeviceEntity). These
    // columns are kept declared (like themeAccentIndex below) so Room need not rebuild app_settings;
    // nothing reads them anymore.
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
    val autoExportOnFinalize: Boolean = false,
    // Legacy v8 field: the single index that used to drive all three accent roles. Superseded by
    // themePrimaryIndex/Secondary/Tertiary below (MIGRATION_8_9 copies its value into all three).
    // Nothing reads it; it stays declared because Room validates app_settings against this entity,
    // so removing the field would require rebuilding the table inside the migration.
    val themeAccentIndex: Int = 0,
    // Indices into ui.theme.accentPresets, one per Material color role. All three equal is the
    // uniform palette the Appearance swatch row applies; the role dialog lets one role deviate.
    // 0 = the default palette, so existing installs keep their current look after migration.
    val themePrimaryIndex: Int = 0,
    val themeSecondaryIndex: Int = 0,
    val themeTertiaryIndex: Int = 0,
    // Color mode as the ordinal of ui.theme.ThemeMode: 0 = System (follow the device), 1 = Light,
    // 2 = Dark. Defaults to System so a fresh install tracks the setting that drives the splash.
    val themeMode: Int = 0,
    // Material You / Android 12+ dynamic color. When true AND the device supports it, AppTheme
    // sources the colorScheme from dynamic{Light,Dark}ColorScheme(context) instead of the curated
    // accent presets; the three theme*Index columns still persist so flipping back restores the
    // exact previous look. Older API levels silently fall through to the presets.
    val themeUseDynamicColor: Boolean = false
)
