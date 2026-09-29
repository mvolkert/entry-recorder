package io.github.mvolkert.entryrecorder.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.data.local.entity.AppSettingsEntity

/** Which events may wake the screen, make sound or vibrate. */
@Composable
internal fun SettingsAlertsCard(
    settings: AppSettingsEntity,
    onSettingsChange: (AppSettingsEntity) -> Unit,
) {
    SettingsCard {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_wake_ring),
            checked = settings.wakeOnRing,
            onCheckedChange = { onSettingsChange(settings.copy(wakeOnRing = it)) }
        )

        SettingsSwitchRow(
            title = stringResource(R.string.settings_sound_ring),
            checked = settings.soundOnRing,
            onCheckedChange = { onSettingsChange(settings.copy(soundOnRing = it)) }
        )

        SettingsSwitchRow(
            title = stringResource(R.string.settings_vibrate_ring),
            checked = settings.vibrateOnRing,
            onCheckedChange = { onSettingsChange(settings.copy(vibrateOnRing = it)) }
        )

        SettingsSwitchRow(
            title = stringResource(R.string.settings_wake_motion),
            checked = settings.wakeOnMotion,
            onCheckedChange = { onSettingsChange(settings.copy(wakeOnMotion = it)) }
        )

        SettingsSwitchRow(
            title = stringResource(R.string.settings_wake_noise),
            checked = settings.wakeOnNoise,
            onCheckedChange = { onSettingsChange(settings.copy(wakeOnNoise = it)) }
        )
    }
}
