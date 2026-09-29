package io.github.mvolkert.entryrecorder.ui.recordings

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.mvolkert.entryrecorder.R

/**
 * Title row of the Recordings screen: either the archive heading with its storage line or, in
 * multi-select mode, the selection count with select-all / export / bulk-delete / exit actions.
 */
@Composable
internal fun RecordingsHeader(
    selectionMode: Boolean,
    selectedCount: Int,
    totalStorageBytes: Long,
    onSelectAll: () -> Unit,
    onExportSelected: (RecordingExportKind) -> Unit,
    onBulkDelete: () -> Unit,
    onExitSelection: () -> Unit,
    onEnterSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showExportMenu by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (selectionMode)
                stringResource(R.string.recordings_selected_count, selectedCount)
            else stringResource(R.string.recordings_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        if (selectionMode) {
            IconButton(onClick = onSelectAll) {
                Icon(Icons.Default.SelectAll, contentDescription = stringResource(R.string.recordings_cd_select_all))
            }
            Box {
                IconButton(
                    onClick = { showExportMenu = true },
                    enabled = selectedCount > 0
                ) {
                    Icon(Icons.Default.Upload, contentDescription = stringResource(R.string.recordings_cd_export_selected))
                }
                DropdownMenu(
                    expanded = showExportMenu,
                    onDismissRequest = { showExportMenu = false }
                ) {
                    BatchExportOption(R.string.recordings_share_batch, Icons.Default.Share) {
                        showExportMenu = false
                        onExportSelected(RecordingExportKind.SHARE)
                    }
                    BatchExportOption(R.string.recordings_save_gallery_menu, Icons.Default.Download) {
                        showExportMenu = false
                        onExportSelected(RecordingExportKind.GALLERY)
                    }
                    BatchExportOption(R.string.recordings_export_folder_batch, Icons.Default.SaveAlt) {
                        showExportMenu = false
                        onExportSelected(RecordingExportKind.FOLDER)
                    }
                }
            }
            IconButton(
                onClick = onBulkDelete,
                enabled = selectedCount > 0
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.recordings_cd_delete_selected),
                    tint = MaterialTheme.colorScheme.error
                )
            }
            IconButton(onClick = onExitSelection) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.recordings_cd_exit_selection))
            }
        } else {
            IconButton(onClick = onEnterSelection) {
                Icon(Icons.Default.Checklist, contentDescription = stringResource(R.string.recordings_cd_select_multiple))
            }
        }
    }

    Text(
        text = stringResource(
            R.string.recordings_total_storage,
            Formatter.formatFileSize(context, totalStorageBytes)
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun BatchExportOption(
    @StringRes labelRes: Int,
    icon: ImageVector,
    onPick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(labelRes)) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onPick
    )
}
