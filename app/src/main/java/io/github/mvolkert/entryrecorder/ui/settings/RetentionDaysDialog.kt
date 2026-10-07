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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.mvolkert.entryrecorder.R

/**
 * Exact day entry for the retention period, which the ±1 steppers alone would need a hundred taps to
 * move that far. Invalid input blocks Save rather than silently clamping; `0` keeps its meaning of
 * "keep indefinitely". Persisting stays with the caller.
 */
@Composable
internal fun RetentionDaysDialog(
    initialDays: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
) {
    var input by remember { mutableStateOf(initialDays.toString()) }
    val days = input.toIntOrNull()
    val isValid = days != null && days in 0..MAX_RETENTION_DAYS

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.settings_retention_dialog_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(stringResource(R.string.settings_retention_days_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = !isValid,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(
                        if (isValid) R.string.settings_retention_dialog_hint
                        else R.string.settings_retention_dialog_invalid
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
                    if (days != null) onSave(days)
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
