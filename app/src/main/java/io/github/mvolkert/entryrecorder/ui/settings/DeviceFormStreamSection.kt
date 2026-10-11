package io.github.mvolkert.entryrecorder.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.data.model.StreamProtocol
import io.github.mvolkert.entryrecorder.ui.components.LoadingSpinner
import kotlin.math.roundToInt

/**
 * How the picture is pulled from the device. The path fields only appear for the protocols that
 * actually use them, and AUTO shows both because it may fall back between them.
 *
 * The burst measurement and the delivered-rate reading come from [SettingsViewModel] as state: a probe
 * outlives this section's recompositions and a rotation alike, and the rate poll is not driven from here
 * at all — the screen says which device it edits, and the ViewModel answers with the measured number.
 */
@Composable
internal fun DeviceFormStreamSection(
    form: DeviceFormState,
    snapshotProbe: SnapshotProbeState,
    measuredFps: Float,
    onMeasureFps: () -> Unit,
) {
    val protocols = listOf(
        StreamProtocol.AUTO,
        StreamProtocol.RTSP,
        StreamProtocol.MJPEG_STREAM,
        StreamProtocol.HTTP_SNAPSHOT,
    )
    FormSingleChoice(
        label = stringResource(R.string.device_protocol_label),
        options = protocols.map {
            stringResource(
                when (it) {
                    StreamProtocol.AUTO -> R.string.device_protocol_auto
                    StreamProtocol.RTSP -> R.string.device_protocol_rtsp
                    StreamProtocol.MJPEG_STREAM -> R.string.device_protocol_mjpeg
                    StreamProtocol.HTTP_SNAPSHOT -> R.string.device_protocol_snapshot
                },
                form.rtspPort,
            )
        },
        selectedIndex = protocols.indexOf(form.streamProtocol),
        onSelect = { form.streamProtocol = protocols[it] },
    )

    AnimatedVisibility(
        visible = form.streamProtocol == StreamProtocol.MJPEG_STREAM ||
            form.streamProtocol == StreamProtocol.AUTO,
        enter = formRevealEnter(),
        exit = formRevealExit(),
    ) {
        OutlinedTextField(
            value = form.mjpegPath,
            onValueChange = { form.mjpegPath = it },
            label = { Text(stringResource(R.string.device_mjpeg_path_label)) },
            placeholder = { Text(stringResource(R.string.device_mjpeg_path_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
    }

    AnimatedVisibility(
        visible = form.streamProtocol == StreamProtocol.HTTP_SNAPSHOT ||
            form.streamProtocol == StreamProtocol.AUTO,
        enter = formRevealEnter(),
        exit = formRevealExit(),
    ) {
        // AnimatedVisibility gives an AnimatedVisibilityScope, not a ColumnScope, so its direct
        // children overlap unless they get their own stacking Column.
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = form.snapshotPath,
                onValueChange = { form.snapshotPath = it },
                label = { Text(stringResource(R.string.device_snapshot_path_label)) },
                placeholder = { Text(stringResource(R.string.device_snapshot_path_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            // One-time burst measurement of what this endpoint can actually serve, so the rate stops
            // being a per-device-type guess. The probe runs in the ViewModel; writing the number into the
            // field above it is the one part only the form can do, because the form owns its own values.
            val probing = snapshotProbe is SnapshotProbeState.Running
            LaunchedEffect(snapshotProbe) {
                val measured = (snapshotProbe as? SnapshotProbeState.Measured)?.fps ?: return@LaunchedEffect
                if (measured > 0f) {
                    form.snapshotFps =
                        measured.roundToInt().coerceIn(1, form.maxSnapshotFps).toString()
                }
            }
            // Rate box and measure button share a row; Top alignment keeps the button level with the
            // input box rather than centered down the taller field+supportingText column.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                OutlinedTextField(
                    value = form.snapshotFps,
                    onValueChange = { form.snapshotFps = it },
                    label = { Text(stringResource(R.string.device_snapshot_fps)) },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = form.isSnapshotFpsAboveCeiling,
                    singleLine = true,
                    supportingText = {
                        Text(
                            text = stringResource(
                                if (form.isSnapshotFpsAboveCeiling) R.string.device_snapshot_fps_above_ceiling
                                else R.string.device_snapshot_fps_ceiling,
                                form.maxSnapshotFps
                            ),
                        )
                    },
                )
                OutlinedButton(
                    onClick = onMeasureFps,
                    enabled = !probing,
                ) {
                    if (probing) {
                        LoadingSpinner(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.device_snapshot_fps_probing))
                    } else {
                        Text(stringResource(R.string.device_snapshot_fps_get))
                    }
                }
            }
            // A probe in flight replaces the previous verdict, so there is nothing to show while Running.
            val probed = (snapshotProbe as? SnapshotProbeState.Measured)?.fps
            if (probed != null) {
                Text(
                    text = if (probed > 0f)
                        stringResource(R.string.device_snapshot_fps_probe_result, probed)
                    else
                        stringResource(R.string.device_snapshot_fps_probe_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // The rate the endpoint really delivered lately, read by the ViewModel from the shared snapshot
            // client while the live view or a recording polls it. Without this the number is guesswork.
            if (measuredFps > 0f) {
                Text(
                    text = stringResource(R.string.device_snapshot_fps_measured, measuredFps),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
