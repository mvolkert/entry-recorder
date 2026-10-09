package io.github.mvolkert.entryrecorder.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.data.model.StreamProtocol
import io.github.mvolkert.entryrecorder.data.network.HttpSnapshotClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/**
 * How the picture is pulled from the device. The path fields only appear for the protocols that
 * actually use them, and AUTO shows both because it may fall back between them.
 */
@Composable
internal fun DeviceFormStreamSection(form: DeviceFormState) {
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
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
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
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        OutlinedTextField(
            value = form.snapshotPath,
            onValueChange = { form.snapshotPath = it },
            label = { Text(stringResource(R.string.device_snapshot_path_label)) },
            placeholder = { Text(stringResource(R.string.device_snapshot_path_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        // One-time burst measurement of what this endpoint can actually serve, so the rate stops being a
        // per-device-type guess. Writes the measured number straight back into the field above it.
        val scope = rememberCoroutineScope()
        var probing by remember(form.deviceId) { mutableStateOf(false) }
        var probeResult by remember(form.deviceId) { mutableStateOf(-1f) }
        OutlinedTextField(
            value = form.snapshotFps,
            onValueChange = { form.snapshotFps = it },
            label = { Text(stringResource(R.string.device_snapshot_fps)) },
            modifier = Modifier.fillMaxWidth(),
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
            onClick = {
                scope.launch {
                    probing = true
                    probeResult = -1f
                    val fps = HttpSnapshotClient.probeSnapshotFps(form.buildTestCandidate())
                    if (fps > 0f) {
                        form.snapshotFps = fps.roundToInt().coerceIn(1, form.maxSnapshotFps).toString()
                    }
                    probeResult = fps
                    probing = false
                }
            },
            enabled = !probing,
        ) {
            if (probing) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.device_snapshot_fps_probing))
            } else {
                Text(stringResource(R.string.device_snapshot_fps_get))
            }
        }
        if (!probing && probeResult >= 0f) {
            Text(
                text = if (probeResult > 0f)
                    stringResource(R.string.device_snapshot_fps_probe_result, probeResult)
                else
                    stringResource(R.string.device_snapshot_fps_probe_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // The rate the endpoint really delivered lately, measured by the shared snapshot client while the
        // live view or a recording polls it. Without this the configured number is unfalsifiable.
        val measuredFps = rememberMeasuredSnapshotFps(form.deviceId)
        if (measuredFps > 0f) {
            Text(
                text = stringResource(R.string.device_snapshot_fps_measured, measuredFps),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Polls the shared snapshot client's measurement for [deviceId] while the dialog is open.
 * Reads the live value rather than a cached one, and only devices that were polled recently report a
 * rate — a new or never-polled device has nothing to show.
 */
@Composable
private fun rememberMeasuredSnapshotFps(deviceId: Long): Float {
    var measured by remember(deviceId) { mutableFloatStateOf(HttpSnapshotClient.achievedFps(deviceId)) }
    LaunchedEffect(deviceId) {
        while (true) {
            measured = HttpSnapshotClient.achievedFps(deviceId)
            delay(2000.milliseconds)
        }
    }
    return measured
}
