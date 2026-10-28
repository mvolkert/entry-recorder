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
    val autoBootEnabled: Boolean = true,
    // Remote motion alert (change `add-motion-notification-to-other-phone`): when enabled and a recipient is
    // set, a detected motion event is relayed through the Python server to one other phone. Off by default.
    val motionAlertEnabled: Boolean = false,
    val motionAlertRecipient: String = "",
    // File logging (change `add-timber-file-logging`): when on, a Timber FileTree is planted and appends the
    // app's log lines to a bounded, rolling file under the app's private files dir. Off by default.
    val loggingToFileEnabled: Boolean = false,
) {
    /**
     * Diagnostic form, mirroring [io.github.mvolkert.entryrecorder.domain.model.Device]’s: replaces the data-class
     * `toString()`, which would print the decrypted `serverApiKey` in clear wherever a settings row is
     * interpolated into a log — the one secret this change’s file capture could otherwise leak.
     */
    override fun toString(): String = buildString {
        append("AppSettings(recordingMode=$recordingMode, serverBaseUrl=$serverBaseUrl")
        append(", serverApiKey=$REDACTED, retentionDays=$retentionDays")
        append(", transcodeOnExport=$transcodeOnExport, exportFolderUri=$exportFolderUri")
        append(", autoExportOnFinalize=$autoExportOnFinalize, internalQuotaMb=$internalQuotaMb")
        append(", internalCleanupEnabled=$internalCleanupEnabled, externalQuotaMb=$externalQuotaMb")
        append(", externalCleanupEnabled=$externalCleanupEnabled, themePrimaryIndex=$themePrimaryIndex")
        append(", themeSecondaryIndex=$themeSecondaryIndex, themeTertiaryIndex=$themeTertiaryIndex")
        append(", themeMode=$themeMode, themeUseDynamicColor=$themeUseDynamicColor")
        append(", autoBootEnabled=$autoBootEnabled, motionAlertEnabled=$motionAlertEnabled")
        append(", motionAlertRecipient=$motionAlertRecipient, loggingToFileEnabled=$loggingToFileEnabled)")
    }

    companion object {
        /**
         * What "no quota chosen yet" means in every place that has to say it: the fresh-install default, the
         * v17 column default in the migration SQL, and where a stepper tap goes when the stored limit is
         * unusable. One definition, so the three cannot disagree about the number that starts a purge.
         */
        const val DEFAULT_INTERNAL_QUOTA_MB = 10_240L
        const val DEFAULT_EXTERNAL_QUOTA_MB = 20_480L

        /** Placeholder the one credential field collapses to in [toString]. */
        private const val REDACTED = "<redacted>"
    }
}
