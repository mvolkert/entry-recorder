package io.github.mvolkert.entryrecorder.ui.recordings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.ui.components.ProgressLine
import io.github.mvolkert.entryrecorder.ui.theme.Spacing

// Progress and confirm surfaces for the recordings list. A delete needs an answer before anything happens,
// so it is a dialog; a running export needs no answer, so it is the strip at the bottom of this file and
// never covers the list.

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

/**
 * Confirmation shown before the multi-select batch is removed. [protectedCount] of the selected rows are
 * protected and survive the delete, so the dialog says so instead of promising more than it will do.
 */
@Composable
internal fun BulkDeleteDialog(
    selectedCount: Int,
    protectedCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.recordings_bulk_delete_title, selectedCount, selectedCount)) },
        text = {
            Column {
                Text(stringResource(R.string.recordings_bulk_delete_body))
                if (protectedCount > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = pluralStringResource(
                            R.plurals.recordings_bulk_delete_protected_hint,
                            protectedCount,
                            protectedCount
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
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

/** One line of the progress strip. A null [percent] is the batch form, whose [label] already counts "k of n". */
internal data class RunDisplay(val percent: Int?, val label: String)

/**
 * The running export, download or batch as one strip over the top of the list, with a real Cancel. It
 * replaces three modal dialogs that covered the whole screen, could not be dismissed and offered no way
 * out at all — a transcode of a long clip runs for minutes, and being able to read the list meanwhile is
 * the difference between waiting and being blocked.
 *
 * A null [run] ends it. The strip keeps showing the last text through its own fade-out because
 * [AnimatedVisibility] does not invoke its content lambda once the flag is false, which would otherwise
 * leave an empty pill shrinking away.
 */
@Composable
internal fun RunProgressStrip(
    run: RunDisplay?,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var lastRun by remember { mutableStateOf(run) }
    if (run != null) lastRun = run

    // Alpha takes the effects spec; the slide keeps the transition's own spatial spring, since the
    // motion scheme's spatial spec is Float-typed and a vertical offset needs an Int.
    val effectsSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()

    AnimatedVisibility(
        visible = run != null,
        enter = fadeIn(animationSpec = effectsSpec) + slideInVertically(initialOffsetY = { -it / 3 }),
        exit = fadeOut(animationSpec = effectsSpec) + slideOutVertically(targetOffsetY = { -it / 3 }),
        modifier = modifier,
        label = "runProgressStrip",
    ) {
        val shown = lastRun ?: return@AnimatedVisibility
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Row(
                modifier = Modifier.padding(
                    start = Spacing.md,
                    top = Spacing.xs,
                    end = Spacing.xs,
                    bottom = Spacing.xs,
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = shown.label,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                    )
                    if (shown.percent != null) {
                        ProgressLine(
                            progress = shown.percent / 100f,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    }
}
