package io.github.mvolkert.entryrecorder.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.util.CleanupPolicy

/**
 * Exact megabyte entry for a storage quota. The ±100 MB steppers would need a hundred taps to travel a
 * gigabyte, so the row's value opens this dialog instead. Save is blocked while the value is outside the
 * selectable range rather than silently clamped: a quota the pass cannot work with is not a quota.
 *
 * Any whole megabyte value is accepted, not only the ones a stepper can land on. The shipped limits
 * (10 240 and 20 480 MB) and every legacy value the v17 migration backfills are multiples of 1024, so a
 * "must be a full 100 MB step" rule would reject the number the row is already showing.
 */
@Composable
internal fun QuotaMbDialog(
    title: String,
    initialQuotaMb: Long,
    onDismiss: () -> Unit,
    onSave: (Long) -> Unit,
) {
    var input by remember { mutableStateOf(initialQuotaMb.toString()) }
    val quotaMb = input.toLongOrNull()
    val isValid = quotaMb != null && CleanupPolicy.inRange(quotaMb)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(title, modifier = Modifier.semantics { heading() })
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(stringResource(R.string.settings_quota_mb_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = !isValid,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(
                        if (isValid) R.string.settings_quota_dialog_hint
                        else R.string.settings_quota_dialog_invalid_range
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isValid) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (quotaMb != null) onSave(quotaMb)
                    onDismiss()
                },
                enabled = isValid
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
