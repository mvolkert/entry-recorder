package io.github.mvolkert.entryrecorder.domain.model

import io.github.mvolkert.entryrecorder.data.model.RecordingMode

/**
 * App-wide preferences — the domain form of the single stored settings row. Field names mirror the
 * entity because callers update one row by copying it with changed values. The entity's declared-but-unread
 * legacy columns (superseded quotas, per-device wake flags, the old single accent index) have no domain
 * counterpart: the mapper re-supplies their defaults when a row is written back.
 */
data class AppSettings(
    val recordingMode: RecordingMode = RecordingMode.APP_LOCAL,
    val serverBaseUrl: String = "http://192.168.1.100:8000",
    val serverApiKey: String = "",
    val retentionDays: Int = 14,
    val transcodeOnExport: Boolean = true,
    val exportFolderUri: String = "",
    val autoExportOnFinalize: Boolean = false,
    val internalQuotaMb: Long = DEFAULT_INTERNAL_QUOTA_MB,
    val internalCleanupEnabled: Boolean = true,
    val externalQuotaMb: Long = DEFAULT_EXTERNAL_QUOTA_MB,
    val externalCleanupEnabled: Boolean = false,
    val themePrimaryIndex: Int = 0,
    val themeSecondaryIndex: Int = 0,
    val themeTertiaryIndex: Int = 0,
    val themeMode: Int = 0,
    val themeUseDynamicColor: Boolean = false,
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
