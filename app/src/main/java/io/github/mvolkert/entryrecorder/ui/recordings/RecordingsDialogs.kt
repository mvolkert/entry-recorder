package io.github.mvolkert.entryrecorder.ui.recordings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.data.local.entity.RecordingEntity

/** Confirmation shown before a single recording is removed. */
@Composable
internal fun DeleteRecordingDialog(
    recording: RecordingEntity,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.recordings_delete_title)) },
        text = { Text(stringResource(R.string.recordings_delete_body, recording.deviceName)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/** Confirmation shown before the multi-select batch is removed. */
@Composable
internal fun BulkDeleteDialog(
    selectedCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.recordings_bulk_delete_title, selectedCount, selectedCount)) },
        text = { Text(stringResource(R.string.recordings_bulk_delete_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/** Modal progress for the H.264 transcode of one recording. Not dismissible while running. */
@Composable
internal fun ExportProgressDialog(progressPercent: Int) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.recordings_export_progress_title)) },
        text = {
            Column {
                Text(stringResource(R.string.recordings_export_progress_body, progressPercent))
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {}
    )
}

/** Modal "k of n" progress for a batch export across the selected recordings. */
@Composable
internal fun BatchProgressDialog(done: Int, total: Int) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.recordings_batch_progress_title)) },
        text = {
            Column {
                Text(stringResource(R.string.recordings_batch_progress_body, done, total))
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {}
    )
}
