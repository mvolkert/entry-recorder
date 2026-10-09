package io.github.mvolkert.entryrecorder.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
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
import io.github.mvolkert.entryrecorder.data.model.DeviceType
import io.github.mvolkert.entryrecorder.data.model.MotionSensitivity

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
    val sources = buildList {
        add(MotionSource.OFF)
        if (form.deviceType == DeviceType.TWO_N_VERSO) add(MotionSource.CAMERA)
        add(MotionSource.APP)
    }
    FormSingleChoice(
        label = stringResource(R.string.device_motion_source_label),
        options = sources.map {
            stringResource(
                when (it) {
                    MotionSource.OFF -> R.string.device_motion_source_off
                    MotionSource.CAMERA -> R.string.device_motion_source_camera
                    MotionSource.APP -> R.string.device_motion_source_app
                }
            )
        },
        selectedIndex = sources.indexOf(form.motionSource).coerceAtLeast(0),
        onSelect = { form.motionSource = sources[it] },
    )
    Text(
        stringResource(R.string.device_motion_source_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    // Sensitivity only steers the in-app analyzer; the camera's own motion events ignore these numbers,
    // so the picker appears only once "Analyzed in-app" is the chosen source.
    AnimatedVisibility(
        visible = form.motionSource == MotionSource.APP,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        val sensitivities = listOf(
            MotionSensitivity.SENSITIVE,
            MotionSensitivity.BALANCED,
            MotionSensitivity.POWER_SAVER,
        )
        FormSingleChoice(
            label = stringResource(R.string.device_motion_sensitivity_label),
            options = sensitivities.map {
                stringResource(
                    when (it) {
                        MotionSensitivity.SENSITIVE -> R.string.device_motion_sensitivity_sensitive
                        MotionSensitivity.BALANCED -> R.string.device_motion_sensitivity_balanced
                        MotionSensitivity.POWER_SAVER -> R.string.device_motion_sensitivity_power_saver
                    }
                )
            },
            selectedIndex = sensitivities.indexOf(form.motionSensitivity),
            onSelect = { form.motionSensitivity = sensitivities[it] },
        )
        Text(
            stringResource(
                when (form.motionSensitivity) {
                    MotionSensitivity.SENSITIVE -> R.string.device_motion_sensitivity_sensitive_hint
                    MotionSensitivity.BALANCED -> R.string.device_motion_sensitivity_balanced_hint
                    MotionSensitivity.POWER_SAVER -> R.string.device_motion_sensitivity_power_saver_hint
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
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
    // Three equal-weight fields share one line; single-line inputs keep them short and aligned so all
    // three fit even on a narrow phone.
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = form.ringRecordSeconds,
            onValueChange = { form.ringRecordSeconds = it },
            label = { Text(stringResource(R.string.device_duration_ring)) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        OutlinedTextField(
            value = form.motionPostRecordSeconds,
            onValueChange = { form.motionPostRecordSeconds = it },
            label = { Text(stringResource(R.string.device_duration_motion_post)) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        OutlinedTextField(
            value = form.noisePostRecordSeconds,
            onValueChange = { form.noisePostRecordSeconds = it },
            label = { Text(stringResource(R.string.device_duration_noise_post)) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
    }

    FormEmphasizedSwitchRow(
        title = stringResource(R.string.device_enabled_toggle),
        checked = form.isEnabled,
        onCheckedChange = { form.isEnabled = it }
    )
}
