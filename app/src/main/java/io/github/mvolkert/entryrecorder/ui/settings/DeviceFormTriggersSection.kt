package io.github.mvolkert.entryrecorder.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.mvolkert.entryrecorder.R

/** Which events start a recording on this device. */
@Composable
internal fun DeviceFormTriggersSection(form: DeviceFormState) {
    FormSectionLabel(R.string.device_triggers_section)
    SettingsSwitchRow(
        title = stringResource(R.string.device_trigger_ring),
        checked = form.recordOnRing,
        onCheckedChange = { form.recordOnRing = it }
    )
    SettingsSwitchRow(
        title = stringResource(R.string.device_trigger_motion),
        checked = form.recordOnMotion,
        onCheckedChange = { form.recordOnMotion = it }
    )
    SettingsSwitchRow(
        title = stringResource(R.string.device_trigger_noise),
        checked = form.recordOnNoise,
        onCheckedChange = { form.recordOnNoise = it }
    )
    Column {
        SettingsSwitchRow(
            title = stringResource(R.string.device_trigger_motion_app),
            checked = form.recordOnMotionOnDevice,
            onCheckedChange = { form.recordOnMotionOnDevice = it }
        )
        Text(
            stringResource(R.string.device_trigger_motion_app_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Per-event lengths plus the master enable switch the monitor service checks before arming a device. */
@Composable
internal fun DeviceFormDurationsSection(form: DeviceFormState) {
    FormSectionLabel(R.string.device_durations_section)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = form.ringRecordSeconds,
            onValueChange = { form.ringRecordSeconds = it },
            label = { Text(stringResource(R.string.device_duration_ring)) },
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        OutlinedTextField(
            value = form.motionPostRecordSeconds,
            onValueChange = { form.motionPostRecordSeconds = it },
            label = { Text(stringResource(R.string.device_duration_motion_post)) },
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        OutlinedTextField(
            value = form.noisePostRecordSeconds,
            onValueChange = { form.noisePostRecordSeconds = it },
            label = { Text(stringResource(R.string.device_duration_noise_post)) },
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
    }

    FormEmphasizedSwitchRow(
        title = stringResource(R.string.device_enabled_toggle),
        checked = form.isEnabled,
        onCheckedChange = { form.isEnabled = it }
    )
}
