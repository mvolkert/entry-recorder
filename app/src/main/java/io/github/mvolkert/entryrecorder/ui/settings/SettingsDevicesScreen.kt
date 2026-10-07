package io.github.mvolkert.entryrecorder.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mvolkert.entryrecorder.R

/**
 * Submenu listing configured intercoms with their capability readouts, plus the Add-device FAB. Edit
 * and add push the fullscreen [DeviceEditScreen] via the [onEditDevice] / [onAddDevice] callbacks.
 */
@Composable
fun SettingsDevicesScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onEditDevice: (Long) -> Unit,
    onAddDevice: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsSubscreenScaffold(
        titleRes = R.string.settings_section_devices,
        onBack = onBack,
        events = viewModel.events,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddDevice,
                icon = { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.settings_cd_add)) },
                text = { Text(stringResource(R.string.settings_add_device)) }
            )
        }
    ) {
        if (state.devices.isEmpty()) {
            DevicesEmptyCard()
        } else {
            state.devices.forEach { device ->
                DeviceCard(
                    device = device,
                    eventQuality = state.eventQualities[device.id],
                    snapshotQuality = state.snapshotQualities[device.id],
                    onEdit = { onEditDevice(device.id) },
                    onDelete = { viewModel.deleteDevice(device) }
                )
            }
        }
        // Bottom breathing room so the last card clears the FAB.
        Spacer(modifier = Modifier.height(72.dp))
    }
}
