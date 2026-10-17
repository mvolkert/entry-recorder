package io.github.mvolkert.entryrecorder.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.domain.model.AppSettings
import io.github.mvolkert.entryrecorder.data.model.RecordingMode
import io.github.mvolkert.entryrecorder.ui.components.LoadingSpinner
import io.github.mvolkert.entryrecorder.ui.components.filterChipLeadingIcon
import io.github.mvolkert.entryrecorder.ui.theme.Spacing

/**
 * Where recordings are captured: on the phone or on the Python server, plus the server endpoint
 * fields and their connection test, which only appear in server mode.
 */
@Composable
internal fun SettingsEngineCard(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    isTestingServer: Boolean,
    onTestServer: () -> Unit,
) {
    SettingsCard {
        Text(stringResource(R.string.settings_mode_question))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            FilterChip(
                selected = settings.recordingMode == RecordingMode.APP_LOCAL,
                onClick = {
                    onSettingsChange(settings.copy(recordingMode = RecordingMode.APP_LOCAL))
                },
                label = { Text(stringResource(R.string.settings_mode_local)) },
                leadingIcon = filterChipLeadingIcon(
                    settings.recordingMode == RecordingMode.APP_LOCAL,
                    Icons.Default.PhoneAndroid,
                )
            )

            FilterChip(
                selected = settings.recordingMode == RecordingMode.PYTHON_SERVER,
                onClick = {
                    onSettingsChange(settings.copy(recordingMode = RecordingMode.PYTHON_SERVER))
                },
                label = { Text(stringResource(R.string.settings_mode_server)) },
                leadingIcon = filterChipLeadingIcon(
                    settings.recordingMode == RecordingMode.PYTHON_SERVER,
                    Icons.Default.Cloud,
                )
            )
        }

        if (settings.recordingMode == RecordingMode.PYTHON_SERVER) {
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = stringResource(R.string.settings_mode_server_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = settings.serverBaseUrl,
                onValueChange = { onSettingsChange(settings.copy(serverBaseUrl = it)) },
                label = { Text(stringResource(R.string.settings_server_url_label)) },
                placeholder = { Text(stringResource(R.string.settings_server_url_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = settings.serverApiKey,
                onValueChange = { onSettingsChange(settings.copy(serverApiKey = it)) },
                label = { Text(stringResource(R.string.settings_server_api_key)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedButton(
                onClick = onTestServer,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isTestingServer
            ) {
                if (isTestingServer) {
                    LoadingSpinner(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    Text(stringResource(R.string.settings_server_testing))
                } else {
                    Icon(Icons.Default.WifiTethering, contentDescription = null)
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    Text(stringResource(R.string.settings_server_test))
                }
            }
        }

        Spacer(modifier = Modifier.height(Spacing.sm))
        SettingsSwitchRow(
            title = stringResource(R.string.settings_autoboot_title),
            subtitle = stringResource(R.string.settings_autoboot_subtitle),
            checked = settings.autoBootEnabled,
            onCheckedChange = { onSettingsChange(settings.copy(autoBootEnabled = it)) },
        )

        Spacer(modifier = Modifier.height(Spacing.sm))
        SettingsSwitchRow(
            title = stringResource(R.string.settings_motion_alert_title),
            subtitle = stringResource(R.string.settings_motion_alert_subtitle),
            checked = settings.motionAlertEnabled,
            onCheckedChange = { onSettingsChange(settings.copy(motionAlertEnabled = it)) },
        )
        if (settings.motionAlertEnabled) {
            OutlinedTextField(
                value = settings.motionAlertRecipient,
                onValueChange = { onSettingsChange(settings.copy(motionAlertRecipient = it)) },
                label = { Text(stringResource(R.string.settings_motion_alert_recipient_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
    }
}
