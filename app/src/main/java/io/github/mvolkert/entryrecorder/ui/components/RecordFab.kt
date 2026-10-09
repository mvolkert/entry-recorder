package io.github.mvolkert.entryrecorder.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.ui.theme.onRecordingStatusColor
import io.github.mvolkert.entryrecorder.ui.theme.recordingStatusColor

/**
 * The manual-record control as an expressive small FAB: the component's own shapes morph on press and
 * the icon springs between record and stop via [MorphingIcon]. Rests on `primaryContainer`, flips to
 * the fixed REC red while recording so the state never depends on color alone (icon + haptic differ).
 */
@Composable
fun RecordFab(
    isRecording: Boolean,
    onToggleRecord: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    SmallFloatingActionButton(
        onClick = {
            haptic.performHapticFeedback(if (isRecording) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
            onToggleRecord()
        },
        modifier = modifier,
        containerColor = if (isRecording) recordingStatusColor else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (isRecording) onRecordingStatusColor else MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        MorphingIcon(
            imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
            contentDescription = stringResource(
                if (isRecording) R.string.live_cd_stop_recording else R.string.live_cd_start_recording
            ),
        )
    }
}
