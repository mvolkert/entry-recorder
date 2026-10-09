package io.github.mvolkert.entryrecorder.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.data.local.entity.AppSettingsEntity
import io.github.mvolkert.entryrecorder.util.ExportFolderAccess
import io.github.mvolkert.entryrecorder.util.ExportHelper

/**
 * Upper bound of the retention setting, so the value stays a sane number for the day-based cutoff
 * arithmetic in the cleanup worker. Shared with the direct-entry dialog.
 */
internal const val MAX_RETENTION_DAYS = 3650

/** How much archive space is used and how the app reclaims it: retention, quota, cleanups, export folder. */
@Composable
internal fun SettingsStorageCard(
    settings: AppSettingsEntity,
    onSettingsChange: (AppSettingsEntity) -> Unit,
    totalStorageBytes: Long,
    exportFolderAccess: ExportFolderAccess,
    onPickExportFolder: () -> Unit,
    onClearExportFolder: () -> Unit,
    onCleanupNow: () -> Unit,
) {
    val context = LocalContext.current
    var showRetentionDialog by remember { mutableStateOf(false) }

    SettingsCard {
        Text(
            text = stringResource(
                R.string.settings_storage_usage,
                Formatter.formatFileSize(context, totalStorageBytes)
            ),
            fontWeight = FontWeight.SemiBold
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

        StepperRow(
            title = stringResource(R.string.settings_storage_quota),
            subtitle = stringResource(R.string.settings_quota_purge, settings.maxStorageUsageMb / 1024),
            value = stringResource(R.string.settings_quota_gb, settings.maxStorageUsageMb / 1024),
            onDecrease = {
                onSettingsChange(
                    settings.copy(maxStorageUsageMb = (settings.maxStorageUsageMb - 1024L).coerceAtLeast(1024L))
                )
            },
            onIncrease = {
                onSettingsChange(settings.copy(maxStorageUsageMb = settings.maxStorageUsageMb + 1024L))
            }
        )

        SettingsSwitchRow(
            title = stringResource(R.string.settings_auto_cleanup),
            checked = settings.autoCleanupEnabled,
            onCheckedChange = { onSettingsChange(settings.copy(autoCleanupEnabled = it)) }
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

        ExportFolderRow(
            folderUri = settings.exportFolderUri,
            access = exportFolderAccess,
            onPick = onPickExportFolder,
            onClear = onClearExportFolder
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

    if (showRetentionDialog) {
        RetentionDaysDialog(
            initialDays = settings.retentionDays,
            onDismiss = { showRetentionDialog = false },
            onSave = { onSettingsChange(settings.copy(retentionDays = it)) }
        )
    }
}

/**
 * Caption + hint on the left, a - / value / + stepper on the right. [onValueClick] makes the value
 * readable and directly editable at the same time; [hint] adds a third line under the subtitle.
 */
@Composable
private fun StepperRow(
    title: String,
    subtitle: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    hint: String? = null,
    onValueClick: (() -> Unit)? = null
) {
    SettingsItemRow(
        leading = {
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
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDecrease) {
                    Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.settings_cd_decrease))
                }
                val valueModifier = if (onValueClick != null) {
                    Modifier.clickable(onClickLabel = stringResource(R.string.settings_retention_period)) { onValueClick() }
                } else {
                    Modifier
                }
                Text(
                    value,
                    modifier = valueModifier,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onIncrease) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.settings_cd_increase))
                }
            }
        }
    )
}

/** SAF tree-URI picker for the mirror/export folder, warning when the stored grant has gone stale. */
@Composable
private fun ExportFolderRow(
    folderUri: String,
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
                    ExportHelper.safFolderDisplayName(folderUri.toUri())
                ),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onPick,
                modifier = Modifier.weight(1f)
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
            if (folderUri.isNotBlank()) {
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.settings_folder_remove), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
