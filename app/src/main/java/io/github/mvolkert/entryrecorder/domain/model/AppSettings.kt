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
    // The alternative archive mirror: transcode each finished recording and export the playable H.264 file
    // instead of the raw MKV. Only one of the two mirrors can be on — see [withoutConflictingAutoExport].
    val autoTranscodeOnFinalize: Boolean = false,
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
    // set, a detected motion event is relayed through the Python server to one other phone. The recipient is
    // that phone's UnifiedPush address — a bearer URL, which is why [toString] collapses it. Off by default.
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
     *
     * `motionAlertRecipient` is redacted for the same reason since the motion-alert change made it a UnifiedPush
     * endpoint: whoever holds that URL can push a notification to the phone, and the token rides in its path.
     */
    override fun toString(): String = buildString {
        append("AppSettings(recordingMode=$recordingMode, serverBaseUrl=$serverBaseUrl")
        append(", serverApiKey=$REDACTED, retentionDays=$retentionDays")
        append(", transcodeOnExport=$transcodeOnExport, exportFolderUri=$exportFolderUri")
        append(", autoExportOnFinalize=$autoExportOnFinalize, autoTranscodeOnFinalize=$autoTranscodeOnFinalize")
        append(", internalQuotaMb=$internalQuotaMb")
        append(", internalCleanupEnabled=$internalCleanupEnabled, externalQuotaMb=$externalQuotaMb")
        append(", externalCleanupEnabled=$externalCleanupEnabled, themePrimaryIndex=$themePrimaryIndex")
        append(", themeSecondaryIndex=$themeSecondaryIndex, themeTertiaryIndex=$themeTertiaryIndex")
        append(", themeMode=$themeMode, themeUseDynamicColor=$themeUseDynamicColor")
        append(", autoBootEnabled=$autoBootEnabled, motionAlertEnabled=$motionAlertEnabled")
        append(", motionAlertRecipient=$REDACTED, loggingToFileEnabled=$loggingToFileEnabled)")
    }

    companion object {
        /**
         * What "no quota chosen yet" means in every place that has to say it: the fresh-install default, the
         * v17 column default in the migration SQL, and where a stepper tap goes when the stored limit is
         * unusable. One definition, so the three cannot disagree about the number that starts a purge.
         */
        const val DEFAULT_INTERNAL_QUOTA_MB = 10_240L
        const val DEFAULT_EXTERNAL_QUOTA_MB = 20_480L

        /** Placeholder the credential fields collapse to in [toString]. */
        private const val REDACTED = "<redacted>"
    }
}

/**
 * The raw-mirror switch as a settings update: turning it on clears the auto-transcode mirror, because two
 * mirrors enabled at once would put both an MKV and an H.264 file in the export folder for every clip.
 */
fun AppSettings.withRawAutoExport(enabled: Boolean): AppSettings =
    if (enabled) copy(autoExportOnFinalize = true, autoTranscodeOnFinalize = false)
    else copy(autoExportOnFinalize = false)

/** The auto-transcode switch as a settings update, clearing the raw mirror the same way. */
fun AppSettings.withAutoTranscodeExport(enabled: Boolean): AppSettings =
    if (enabled) copy(autoExportOnFinalize = false, autoTranscodeOnFinalize = true)
    else copy(autoTranscodeOnFinalize = false)

/**
 * A stored row that carries both mirrors — only reachable from an older or hand-edited backup, since each
 * switch clears the other — resolved to the auto-transcode mirror, so exactly one artifact per recording is
 * produced either way.
 */
fun AppSettings.normalizedAutoExportPair(): AppSettings =
    if (autoExportOnFinalize && autoTranscodeOnFinalize) copy(autoExportOnFinalize = false) else this
