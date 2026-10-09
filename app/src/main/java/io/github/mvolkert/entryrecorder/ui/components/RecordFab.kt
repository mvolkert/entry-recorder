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
import io.github.mvolkert.entryrecorder.ui.theme.recordingGlyphColor
import io.github.mvolkert.entryrecorder.ui.theme.recordingStatusColor

/**
 * The manual-record control as an expressive small FAB: the component's own shapes morph on press and
 * the icon springs between record and stop via [MorphingIcon]. Recording is red and nothing else: the
 * resting control sits on a neutral and carries the REC glyph in the fixed [recordingGlyphColor] red,
 * while recording fills with the [recordingStatusColor] red and a white stop glyph. No accent color is
 * involved either way, and the state never depends on color alone (icon + haptic differ).
 */
@Composable
fun RecordFab(
    isRecording: Boolean,
    onToggleRecord: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    // The glyph color is handed to MorphingIcon as an explicit tint as well as through contentColor:
    // Material's Icon skips tinting entirely when its tint is unspecified, so a material icon left to
    // inherit would draw in its baked black and the record red would never reach the control.
    val glyphColor = if (isRecording) onRecordingStatusColor else recordingGlyphColor()
    SmallFloatingActionButton(
        onClick = {
            haptic.performHapticFeedback(if (isRecording) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
            onToggleRecord()
        },
        modifier = modifier,
        // surfaceBright is the neutral that reads raised on the Live card wherever the FAB sits: the card
        // itself is surfaceContainerHighest, and surfaceBright is brighter than every container role in both
        // modes, so the disc separates from the card by several tones in light and one step in dark.
        containerColor = if (isRecording) recordingStatusColor else MaterialTheme.colorScheme.surfaceBright,
        contentColor = glyphColor,
    ) {
        MorphingIcon(
            imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
            contentDescription = stringResource(
                if (isRecording) R.string.live_cd_stop_recording else R.string.live_cd_start_recording
            ),
            tint = glyphColor,
        )
    }
}
