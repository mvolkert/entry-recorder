package io.github.mvolkert.entryrecorder.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.data.model.StreamProtocol

/**
 * How the picture is pulled from the device. The path fields only appear for the protocols that
 * actually use them, and AUTO shows both because it may fall back between them.
 */
@Composable
internal fun DeviceFormStreamSection(form: DeviceFormState) {
    FormSectionLabel(R.string.device_protocol_label)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FormRadioRow(
            selected = form.streamProtocol == StreamProtocol.AUTO,
            label = stringResource(R.string.device_protocol_auto),
            onClick = { form.streamProtocol = StreamProtocol.AUTO }
        )
        FormRadioRow(
            selected = form.streamProtocol == StreamProtocol.RTSP,
            label = stringResource(R.string.device_protocol_rtsp, form.rtspPort),
            onClick = { form.streamProtocol = StreamProtocol.RTSP }
        )
        FormRadioRow(
            selected = form.streamProtocol == StreamProtocol.MJPEG_STREAM,
            label = stringResource(R.string.device_protocol_mjpeg),
            onClick = { form.streamProtocol = StreamProtocol.MJPEG_STREAM }
        )
        FormRadioRow(
            selected = form.streamProtocol == StreamProtocol.HTTP_SNAPSHOT,
            label = stringResource(R.string.device_protocol_snapshot),
            onClick = { form.streamProtocol = StreamProtocol.HTTP_SNAPSHOT }
        )
    }

    if (form.streamProtocol == StreamProtocol.MJPEG_STREAM || form.streamProtocol == StreamProtocol.AUTO) {
        OutlinedTextField(
            value = form.mjpegPath,
            onValueChange = { form.mjpegPath = it },
            label = { Text(stringResource(R.string.device_mjpeg_path_label)) },
            modifier = Modifier.fillMaxWidth()
        )
    }

    if (form.streamProtocol == StreamProtocol.HTTP_SNAPSHOT || form.streamProtocol == StreamProtocol.AUTO) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = form.snapshotPath,
                onValueChange = { form.snapshotPath = it },
                label = { Text(stringResource(R.string.device_snapshot_path_label)) },
                modifier = Modifier.weight(2f)
            )
            OutlinedTextField(
                value = form.snapshotFps,
                onValueChange = { form.snapshotFps = it },
                label = { Text(stringResource(R.string.device_snapshot_fps)) },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }
    }
}
