package io.github.mvolkert.entryrecorder.ui.recordings

import androidx.annotation.StringRes
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

/** Confirmation shown before a single recording (local or server) is removed. */
@Composable
internal fun DeleteRecordingDialog(
    deviceName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.recordings_delete_title)) },
        text = { Text(stringResource(R.string.recordings_delete_body, deviceName)) },
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

/**
 * Modal progress for the H.264 transcode or the SAF copy of one recording, whichever the running
 * export is on; [bodyRes] selects the percent line's wording. Not dismissible while running.
 */
@Composable
internal fun ExportProgressDialog(progressPercent: Int, @StringRes bodyRes: Int) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.recordings_export_progress_title)) },
        text = {
            Column {
                Text(stringResource(bodyRes, progressPercent))
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {}
    )
}

/** Modal progress for downloading one server recording to the device before it is exported. */
@Composable
internal fun DownloadProgressDialog(progressPercent: Int) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.recordings_download_progress_title)) },
        text = {
            Column {
                Text(stringResource(R.string.recordings_download_progress_body, progressPercent))
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
