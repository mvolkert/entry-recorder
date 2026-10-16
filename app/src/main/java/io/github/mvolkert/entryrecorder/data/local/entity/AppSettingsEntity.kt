package io.github.mvolkert.entryrecorder.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.mvolkert.entryrecorder.data.local.crypto.EncryptedString
import io.github.mvolkert.entryrecorder.data.model.RecordingMode
import io.github.mvolkert.entryrecorder.util.CleanupPolicy

@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey
    val id: Int = 1,
    val recordingMode: RecordingMode = RecordingMode.APP_LOCAL,
    val serverBaseUrl: String = "http://192.168.1.100:8000",
    val serverApiKey: EncryptedString = EncryptedString(""),
    val retentionDays: Int = 14,             // Auto-delete recordings older than N days (0 = disabled)
    // The whole-archive byte limit that used to drive the only purge. Superseded by internalQuotaMb /
    // externalQuotaMb below, which MIGRATION_16_17 backfills from it. Kept declared (like
    // themeAccentIndex) so Room need not rebuild app_settings; nothing reads it anymore.
    val maxStorageUsageMb: Long = 10240,
    // The old master switch for the daily pass. Superseded by the two per-scope switches below;
    // MIGRATION_16_17 seeds internalCleanupEnabled from it so an install that had cleanup turned off does
    // not start deleting. Kept declared for the same reason; nothing reads it anymore.
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
    // Cap on the app's private storage (recordings plus thumbnails), stepped in 100 MB units. The daily
    // purge deletes oldest-unprotected recordings down to one step below this, so it is a working set
    // limit rather than a hard wall.
    val internalQuotaMb: Long = DEFAULT_INTERNAL_QUOTA_MB,
    // Whether the daily pass may reclaim the internal quota at all. Independent of the external flag so
    // an archive mirror on a NAS can keep its history while the phone stays small.
    val internalCleanupEnabled: Boolean = true,
    // Cap on the SAF export folder, measured over the direct children the app wrote there. Off by
    // default: that folder is the long-term archive sync tools read, so reclaiming space in it is
    // something the user has to ask for.
    val externalQuotaMb: Long = DEFAULT_EXTERNAL_QUOTA_MB,
    val externalCleanupEnabled: Boolean = false,
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
) {
    companion object {
        /**
         * What "no quota chosen yet" means in every place that has to say it: the fresh-install default, the
         * v17 column default in the migration SQL, and where a stepper tap goes when the stored limit is
         * unusable. One definition, so the three cannot disagree about the number that starts a purge.
         */
        const val DEFAULT_INTERNAL_QUOTA_MB = 10_240L
        const val DEFAULT_EXTERNAL_QUOTA_MB = 20_480L
    }
}

/**
 * Settings restored from a backup file, with a quota too small to be a limit brought back to the current
 * default. Gson writes only the fields the file mentions, so an older, edited or corrupt file can name `0`
 * where a quota belongs — and a quota of 0 reads to the cleanup arithmetic as "the archive may hold
 * nothing". A value above the selectable cap is deliberately left alone: it is inert in the arithmetic, and
 * the settings row says it is unusable instead of rewriting a limit the user had set.
 */
fun AppSettingsEntity.normalizedForRestore(): AppSettingsEntity {
    val defaults = AppSettingsEntity()
    return copy(
        internalQuotaMb = if (internalQuotaMb >= CleanupPolicy.MIN_QUOTA_MB) internalQuotaMb
        else defaults.internalQuotaMb,
        externalQuotaMb = if (externalQuotaMb >= CleanupPolicy.MIN_QUOTA_MB) externalQuotaMb
        else defaults.externalQuotaMb,
    )
}
