package io.github.mvolkert.entryrecorder.ui.settings

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.data.local.entity.AppSettingsEntity
import io.github.mvolkert.entryrecorder.ui.adaptive.LocalWindowInfo
import io.github.mvolkert.entryrecorder.ui.adaptive.WindowInfo
import io.github.mvolkert.entryrecorder.util.CleanupPolicy
import io.github.mvolkert.entryrecorder.util.ExportFolderAccess
import io.github.mvolkert.entryrecorder.util.StorageUsage

/**
 * Upper bound of the retention setting, so the value stays a sane number for the day-based cutoff
 * arithmetic in the cleanup worker. Shared with the direct-entry dialog.
 */
internal const val MAX_RETENTION_DAYS = 3650

/** Which of the two independent quotas the entry dialog is editing. */
private enum class QuotaScope { Internal, External }

/**
 * Storage settings, split by where the bytes live: the app's private directory, and the SAF export folder
 * that mirrors recordings into it. Each half owns its quota, its cleanup switch and its own direct-entry
 * dialog, because the phone and the archive are allowed to have completely different limits.
 */
@Composable
internal fun SettingsStorageCard(
    settings: AppSettingsEntity,
    onSettingsChange: (AppSettingsEntity) -> Unit,
    internalUsage: StorageUsage,
    externalUsageBytes: Long?,
    exportFolderAccess: ExportFolderAccess,
    exportFolderName: String,
    onPickExportFolder: () -> Unit,
    onClearExportFolder: () -> Unit,
    onCleanupNow: () -> Unit,
) {
    val context = LocalContext.current
    var showRetentionDialog by remember { mutableStateOf(false) }
    var editingQuota by remember { mutableStateOf<QuotaScope?>(null) }

    SettingsCard {
        Text(
            text = stringResource(R.string.settings_section_internal_storage),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = stringResource(
                R.string.settings_storage_usage,
                Formatter.formatFileSize(context, internalUsage.totalBytes)
            ),
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = stringResource(
                R.string.settings_internal_usage_split,
                Formatter.formatFileSize(context, internalUsage.recordingsBytes),
                Formatter.formatFileSize(context, internalUsage.thumbnailBytes)
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        StepperRow(
            title = stringResource(R.string.settings_retention_period),
            subtitle = if (settings.retentionDays == 0)
                stringResource(R.string.settings_retention_indefinite)
            else pluralStringResource(
                R.plurals.settings_retention_days,
                settings.retentionDays,
                settings.retentionDays
            ),
            hint = stringResource(R.string.settings_retention_period_edit_hint),
            value = stringResource(R.string.settings_retention_days_short, settings.retentionDays),
            onValueClick = { showRetentionDialog = true },
            valueClickLabel = stringResource(R.string.settings_retention_period),
            onDecrease = {
                onSettingsChange(
                    settings.copy(retentionDays = (settings.retentionDays - 1).coerceAtLeast(0))
                )
            },
            onIncrease = {
                onSettingsChange(
                    settings.copy(retentionDays = (settings.retentionDays + 1).coerceAtMost(MAX_RETENTION_DAYS))
                )
            }
        )

        QuotaStepperRow(
            title = stringResource(R.string.settings_storage_quota),
            subtitle = stringResource(
                R.string.settings_quota_purge,
                quotaLabel(settings.internalQuotaMb)
            ),
            quotaMb = settings.internalQuotaMb,
            unusableFallbackMb = AppSettingsEntity.DEFAULT_INTERNAL_QUOTA_MB,
            onValueClick = { editingQuota = QuotaScope.Internal },
            onQuotaChange = { onSettingsChange(settings.copy(internalQuotaMb = it)) }
        )

        SettingsSwitchRow(
            title = stringResource(R.string.settings_internal_cleanup),
            subtitle = stringResource(R.string.settings_internal_cleanup_hint),
            checked = settings.internalCleanupEnabled,
            onCheckedChange = { onSettingsChange(settings.copy(internalCleanupEnabled = it)) }
        )

        OutlinedButton(
            onClick = onCleanupNow,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.CleaningServices, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.settings_cleanup_now))
        }
    }

    SettingsCard {
        Text(
            text = stringResource(R.string.settings_section_external_storage),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = stringResource(R.string.settings_external_usage_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = externalUsageLabel(context, exportFolderAccess, externalUsageBytes),
            fontWeight = FontWeight.SemiBold
        )

        QuotaStepperRow(
            title = stringResource(R.string.settings_external_quota),
            subtitle = stringResource(
                R.string.settings_quota_purge_mirror,
                quotaLabel(settings.externalQuotaMb)
            ),
            quotaMb = settings.externalQuotaMb,
            unusableFallbackMb = AppSettingsEntity.DEFAULT_EXTERNAL_QUOTA_MB,
            onValueClick = { editingQuota = QuotaScope.External },
            onQuotaChange = { onSettingsChange(settings.copy(externalQuotaMb = it)) }
        )

        SettingsSwitchRow(
            title = stringResource(R.string.settings_external_cleanup),
            subtitle = stringResource(R.string.settings_external_cleanup_hint),
            checked = settings.externalCleanupEnabled,
            onCheckedChange = { onSettingsChange(settings.copy(externalCleanupEnabled = it)) }
        )

        ExportFolderRow(
            folderUri = settings.exportFolderUri,
            folderName = exportFolderName,
            access = exportFolderAccess,
            onPick = onPickExportFolder,
            onClear = onClearExportFolder
        )

        SettingsSwitchRow(
            title = stringResource(R.string.settings_transcode_export),
            subtitle = stringResource(R.string.settings_transcode_export_hint),
            checked = settings.transcodeOnExport,
            onCheckedChange = { onSettingsChange(settings.copy(transcodeOnExport = it)) }
        )

        SettingsSwitchRow(
            title = stringResource(R.string.settings_auto_export),
            subtitle = stringResource(R.string.settings_auto_export_hint),
            checked = settings.autoExportOnFinalize,
            onCheckedChange = { onSettingsChange(settings.copy(autoExportOnFinalize = it)) }
        )
    }

    if (showRetentionDialog) {
        RetentionDaysDialog(
            initialDays = settings.retentionDays,
            onDismiss = { showRetentionDialog = false },
            onSave = { onSettingsChange(settings.copy(retentionDays = it)) }
        )
    }

    val scope = editingQuota
    if (scope != null) {
        val current = when (scope) {
            QuotaScope.Internal -> settings.internalQuotaMb
            QuotaScope.External -> settings.externalQuotaMb
        }
        QuotaMbDialog(
            title = stringResource(
                when (scope) {
                    QuotaScope.Internal -> R.string.settings_storage_quota
                    QuotaScope.External -> R.string.settings_external_quota
                }
            ),
            initialQuotaMb = current,
            onDismiss = { editingQuota = null },
            onSave = { quotaMb ->
                onSettingsChange(
                    when (scope) {
                        QuotaScope.Internal -> settings.copy(internalQuotaMb = quotaMb)
                        QuotaScope.External -> settings.copy(externalQuotaMb = quotaMb)
                    }
                )
                editingQuota = null
            }
        )
    }
}

/** The quota on its own line: whole gigabytes where that reads better, megabytes otherwise. */
@Composable
private fun quotaLabel(quotaMb: Long): String {
    val gigabytes = CleanupPolicy.wholeGigabytes(quotaMb)
    return if (gigabytes != null) stringResource(R.string.settings_quota_gb, gigabytes)
    else stringResource(R.string.settings_quota_mb, quotaMb)
}

/** What the export folder currently costs, or why it cannot be measured. */
@Composable
private fun externalUsageLabel(
    context: Context,
    access: ExportFolderAccess,
    usageBytes: Long?,
): String {
    // The probe has not answered yet: say "measuring", exactly as the hub row does, not "not reachable".
    if (access == ExportFolderAccess.UNKNOWN) return stringResource(R.string.settings_external_usage_measuring)
    val bytes = usageBytes
    return if (access != ExportFolderAccess.OK || bytes == null) {
        // A missing number behind a live grant is a listing the provider refused; anything else is no grant.
        stringResource(R.string.settings_external_usage_unavailable)
    } else {
        stringResource(R.string.settings_external_usage, Formatter.formatFileSize(context, bytes))
    }
}

/**
 * A quota row: the stepper moves one 100 MB step per tap, the value opens exact entry. Both are clamped by
 * [CleanupPolicy], so the number on screen is always one the purge will honour — except when the stored
 * value is not in range at all, which the row states instead of pretending to be a limit.
 */
@Composable
private fun QuotaStepperRow(
    title: String,
    subtitle: String,
    quotaMb: Long,
    unusableFallbackMb: Long,
    onValueClick: () -> Unit,
    onQuotaChange: (Long) -> Unit,
) {
    val usable = CleanupPolicy.inRange(quotaMb)
    StepperRow(
        title = title,
        subtitle = subtitle,
        hint = if (usable) null else stringResource(R.string.settings_quota_unusable),
        value = quotaLabel(quotaMb),
        onValueClick = onValueClick,
        valueClickLabel = title,
        onDecrease = { onQuotaChange(CleanupPolicy.nextQuotaMb(quotaMb, -1, unusableFallbackMb)) },
        onIncrease = { onQuotaChange(CleanupPolicy.nextQuotaMb(quotaMb, 1, unusableFallbackMb)) }
    )
}

/**
 * Caption + hint on the left, a - / value / + stepper on the right. [onValueClick] makes the value
 * readable and directly editable at the same time; [hint] adds a third line under the subtitle.
 *
 * On Compact the control drops to its own right-aligned line instead of competing with the label for
 * width: two 48 dp icon buttons plus the value would otherwise push a localized caption off the edge.
 */
@Composable
private fun StepperRow(
    title: String,
    subtitle: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    hint: String? = null,
    onValueClick: (() -> Unit)? = null,
    valueClickLabel: String? = null,
) {
    val captions: @Composable ColumnScope.() -> Unit = {
        Text(title, fontWeight = FontWeight.Medium)
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    val controls: @Composable () -> Unit = {
        StepperControls(
            value = value,
            onValueClick = onValueClick,
            valueClickLabel = valueClickLabel,
            onDecrease = onDecrease,
            onIncrease = onIncrease,
        )
    }

    if (LocalWindowInfo.current == WindowInfo.Compact) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            captions()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                controls()
            }
        }
    } else {
        SettingsItemRow(leading = captions, trailing = controls)
    }
}

@Composable
private fun StepperControls(
    value: String,
    onValueClick: (() -> Unit)?,
    valueClickLabel: String?,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onDecrease) {
            Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.settings_cd_decrease))
        }
        val valueModifier = if (onValueClick != null) {
            Modifier.clickable(onClickLabel = valueClickLabel.orEmpty()) { onValueClick() }
        } else {
            Modifier
        }
        Text(
            value,
            modifier = valueModifier,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.MiddleEllipsis
        )
        IconButton(onClick = onIncrease) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.settings_cd_increase))
        }
    }
}

/** SAF tree-URI picker for the mirror/export folder, warning when the stored grant has gone stale. */
@Composable
private fun ExportFolderRow(
    folderUri: String,
    folderName: String,
    access: ExportFolderAccess,
    onPick: () -> Unit,
    onClear: () -> Unit
) {
    val unusable = access == ExportFolderAccess.NO_PERMISSION || access == ExportFolderAccess.MISSING

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(stringResource(R.string.settings_export_folder), fontWeight = FontWeight.Medium)
        Text(
            text = stringResource(R.string.settings_export_folder_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (folderUri.isNotBlank()) {
            Text(
                text = stringResource(
                    R.string.settings_export_folder_selected,
                    folderName
                ),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (unusable) {
                Text(
                    text = stringResource(
                        if (access == ExportFolderAccess.MISSING) R.string.settings_export_folder_missing
                        else R.string.settings_export_folder_revoked
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        val buttons: @Composable (Modifier) -> Unit = { rowModifier ->
            OutlinedButton(
                onClick = onPick,
                modifier = rowModifier
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(
                        when {
                            folderUri.isBlank() -> R.string.settings_folder_choose
                            unusable -> R.string.settings_folder_grant_again
                            else -> R.string.settings_folder_change
                        }
                    )
                )
            }
        }
        if (LocalWindowInfo.current == WindowInfo.Compact) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                buttons(Modifier.fillMaxWidth())
                if (folderUri.isNotBlank()) {
                    RemoveFolderLink(onClear = onClear)
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                buttons(Modifier.weight(1f))
                if (folderUri.isNotBlank()) {
                    RemoveFolderLink(onClear = onClear)
                }
            }
        }
    }
}

@Composable
private fun RemoveFolderLink(onClear: () -> Unit) {
    TextButton(onClick = onClear) {
        Text(stringResource(R.string.settings_folder_remove), color = MaterialTheme.colorScheme.error)
    }
}
