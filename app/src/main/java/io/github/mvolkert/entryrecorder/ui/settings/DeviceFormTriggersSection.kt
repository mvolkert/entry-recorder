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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.data.model.DeviceType

/** Which events start a recording on this device. */
@Composable
internal fun DeviceFormTriggersSection(form: DeviceFormState) {
    FormSectionLabel(R.string.device_triggers_section)
    SettingsSwitchRow(
        title = stringResource(R.string.device_trigger_ring),
        subtitle = stringResource(R.string.device_trigger_ring_hint),
        checked = form.recordOnRing,
        onCheckedChange = { form.recordOnRing = it }
    )
    SettingsSwitchRow(
        title = stringResource(R.string.device_trigger_noise),
        subtitle = stringResource(R.string.device_trigger_noise_hint),
        checked = form.recordOnNoise,
        onCheckedChange = { form.recordOnNoise = it }
    )

    // Motion source is one exhaustive choice, not two look-alike switches that could both be on.
    // Generic RTSP/ONVIF cameras expose no event bus, so "From camera" motion cannot exist there and
    // is not offered.
    Text(
        stringResource(R.string.device_motion_source_label),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FormRadioRow(
            selected = form.motionSource == MotionSource.OFF,
            label = stringResource(R.string.device_motion_source_off),
            onClick = { form.motionSource = MotionSource.OFF }
        )
        if (form.deviceType == DeviceType.TWO_N_VERSO) {
            FormRadioRow(
                selected = form.motionSource == MotionSource.CAMERA,
                label = stringResource(R.string.device_motion_source_camera),
                onClick = { form.motionSource = MotionSource.CAMERA }
            )
        }
        FormRadioRow(
            selected = form.motionSource == MotionSource.APP,
            label = stringResource(R.string.device_motion_source_app),
            onClick = { form.motionSource = MotionSource.APP }
        )
    }
    Text(
        stringResource(R.string.device_motion_source_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Per-device alert & lockscreen behavior for the events this camera can produce. */
@Composable
internal fun DeviceFormAlertsSection(form: DeviceFormState) {
    FormSectionLabel(R.string.settings_section_alerts)
    SettingsSwitchRow(
        title = stringResource(R.string.settings_wake_ring),
        subtitle = stringResource(R.string.settings_wake_ring_summary),
        checked = form.wakeOnRing,
        onCheckedChange = { form.wakeOnRing = it }
    )
    SettingsSwitchRow(
        title = stringResource(R.string.settings_sound_ring),
        checked = form.soundOnRing,
        onCheckedChange = { form.soundOnRing = it }
    )
    SettingsSwitchRow(
        title = stringResource(R.string.settings_vibrate_ring),
        checked = form.vibrateOnRing,
        onCheckedChange = { form.vibrateOnRing = it }
    )
    SettingsSwitchRow(
        title = stringResource(R.string.settings_wake_motion),
        checked = form.wakeOnMotion,
        onCheckedChange = { form.wakeOnMotion = it }
    )
    SettingsSwitchRow(
        title = stringResource(R.string.settings_wake_noise),
        checked = form.wakeOnNoise,
        onCheckedChange = { form.wakeOnNoise = it }
    )
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
