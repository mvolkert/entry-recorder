package io.github.mvolkert.entryrecorder.ui.settings

import androidx.compose.animation.AnimatedVisibility
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
import io.github.mvolkert.entryrecorder.data.model.MotionSensitivity
import io.github.mvolkert.entryrecorder.data.model.supportsCameraEvents

/** Which events start a recording on this device. */
@Composable
internal fun DeviceFormTriggersSection(form: DeviceFormState) {
    FormSectionLabel(R.string.device_triggers_section)
    val cameraEventsDeliverable = supportsCameraEvents(form.deviceType)
    val unavailableReason = stringResource(R.string.device_events_transport_unavailable_reason)
    SettingsSwitchRow(
        title = stringResource(R.string.device_trigger_ring),
        subtitle = stringResource(R.string.device_trigger_ring_hint),
        checked = form.recordOnRing,
        onCheckedChange = { form.recordOnRing = it }
    )
    if (cameraEventsDeliverable) {
        SettingsSwitchRow(
            title = stringResource(R.string.device_trigger_noise),
            subtitle = stringResource(R.string.device_trigger_noise_hint),
            checked = form.recordOnNoise,
            onCheckedChange = { form.recordOnNoise = it }
        )
    } else {
        SettingsSwitchUnavailableRow(
            title = stringResource(R.string.device_trigger_noise),
            reason = unavailableReason,
        )
    }

    // Motion source is one exhaustive choice, not two look-alike switches that could both be on.
    // The camera option is offered only when the transport can actually deliver MotionDetected; when
    // it cannot, the option is still named below the picker so the user sees what they are missing
    // and why, rather than a control that silently refuses to appear.
    val sources = buildList {
        add(MotionSource.OFF)
        if (cameraEventsDeliverable) add(MotionSource.CAMERA)
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
    if (!cameraEventsDeliverable) {
        SettingsSwitchUnavailableRow(
            title = stringResource(R.string.device_motion_source_camera),
            reason = unavailableReason,
        )
    }

    // Sensitivity only steers the in-app analyzer; the camera's own motion events ignore these numbers,
    // so the picker appears only once "Analyzed in-app" is the chosen source.
    AnimatedVisibility(
        visible = form.motionSource == MotionSource.APP,
        enter = formRevealEnter(),
        exit = formRevealExit(),
    ) {
        // AnimatedVisibility gives an AnimatedVisibilityScope, not a ColumnScope, so the picker and
        // its explanation would otherwise overlap instead of stacking.
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            // The pre-roll ring belongs to the analyzer, so it is edited where the analyzer is configured;
            // save clamps the typed count into the supported range like the snapshot rate.
            OutlinedTextField(
                value = form.motionPreRollFrames,
                onValueChange = { form.motionPreRollFrames = it },
                label = { Text(stringResource(R.string.device_motion_preroll_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Text(
                stringResource(R.string.device_motion_preroll_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Per-device alert & lockscreen behavior for the events this camera can produce. */
@Composable
internal fun DeviceFormAlertsSection(form: DeviceFormState) {
    FormSectionLabel(R.string.settings_section_alerts)
    val cameraEventsDeliverable = supportsCameraEvents(form.deviceType)
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
    if (cameraEventsDeliverable) {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_wake_noise),
            checked = form.wakeOnNoise,
            onCheckedChange = { form.wakeOnNoise = it }
        )
    } else {
        SettingsSwitchUnavailableRow(
            title = stringResource(R.string.settings_wake_noise),
            reason = stringResource(R.string.device_events_transport_unavailable_reason),
        )
    }
    // Lock scoping is a deployment choice, not an event capability: idle monitoring holds no lock
    // unless this device opts in, so the row is always editable.
    SettingsSwitchRow(
        title = stringResource(R.string.device_keep_locks_label),
        subtitle = stringResource(R.string.device_keep_locks_hint),
        checked = form.keepLocksHeld,
        onCheckedChange = { form.keepLocksHeld = it }
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

    // The hard ceilings a trigger may not pass. Saved as 0 = no limit, so an untouched device records
    // exactly as long as it did before the fields existed.
    FormSectionLabel(R.string.device_limits_subheader)
    FormTwoFieldRow(
        first = {
            OutlinedTextField(
                value = form.motionMaxFrames,
                onValueChange = { form.motionMaxFrames = it },
                label = { Text(stringResource(R.string.device_max_frames_motion_label)) },
                modifier = it,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        },
        second = {
            OutlinedTextField(
                value = form.noiseMaxFrames,
                onValueChange = { form.noiseMaxFrames = it },
                label = { Text(stringResource(R.string.device_max_frames_noise_label)) },
                modifier = it,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        },
    )
    OutlinedTextField(
        value = form.ringMaxMinutes,
        onValueChange = { form.ringMaxMinutes = it },
        label = { Text(stringResource(R.string.device_max_minutes_ring_label)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
    Text(
        stringResource(R.string.device_record_limit_unlimited_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    FormEmphasizedSwitchRow(
        title = stringResource(R.string.device_enabled_toggle),
        checked = form.isEnabled,
        onCheckedChange = { form.isEnabled = it }
    )
}
