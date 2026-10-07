package io.github.mvolkert.entryrecorder.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Doorbell
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.data.model.ConnectionQuality
import io.github.mvolkert.entryrecorder.data.model.DeviceType
import io.github.mvolkert.entryrecorder.ui.theme.sipModeLabel

/**
 * One configured intercom: identity, network/SIP summary, the per-path capability the monitor service
 * last measured, and the edit / delete actions. The two capability lines separate the camera's own
 * event stream from the snapshot path, because one can be down while the other works — which is why a
 * camera-driven trigger can silently never fire even though the device looks "online".
 */
@Composable
internal fun DeviceCard(
    device: DeviceEntity,
    eventQuality: ConnectionQuality?,
    snapshotQuality: ConnectionQuality?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Doorbell,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(
                        R.string.settings_device_ip_line,
                        device.ipAddress, device.httpPort, device.rtspPort
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = stringResource(
                        R.string.settings_device_sip_line,
                        sipModeLabel(device.sipMode), device.sipLocalPort
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Two independent delivery paths, shown apart so a dead camera event stream (which only
                // breaks ring/camera-motion/noise) is not confused with a working snapshot path.
                val eventSupported = device.deviceType == DeviceType.TWO_N_VERSO
                val eventValueRes = when {
                    !eventSupported -> R.string.status_not_supported
                    eventQuality == ConnectionQuality.ONLINE -> R.string.status_online
                    eventQuality == ConnectionQuality.DEGRADED -> R.string.status_reduced
                    eventQuality == ConnectionQuality.OFFLINE -> R.string.status_offline
                    else -> R.string.status_unknown
                }
                CapabilityLine(
                    formatRes = R.string.settings_device_event_status,
                    valueRes = eventValueRes,
                    offline = eventSupported && eventQuality == ConnectionQuality.OFFLINE
                )
                val snapshotValueRes = when (snapshotQuality) {
                    ConnectionQuality.ONLINE -> R.string.status_online
                    ConnectionQuality.DEGRADED -> R.string.status_reduced
                    ConnectionQuality.OFFLINE -> R.string.status_offline
                    null -> R.string.status_unknown
                }
                CapabilityLine(
                    formatRes = R.string.settings_device_snapshot_status,
                    valueRes = snapshotValueRes,
                    offline = snapshotQuality == ConnectionQuality.OFFLINE
                )
            }

            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.settings_cd_edit))
            }

            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.settings_cd_delete), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Capability summary line; the whole line turns red only when that path is fully offline. */
@Composable
private fun CapabilityLine(
    @StringRes formatRes: Int,
    @StringRes valueRes: Int,
    offline: Boolean
) {
    Text(
        text = stringResource(formatRes, stringResource(valueRes)),
        style = MaterialTheme.typography.bodySmall,
        color = if (offline) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Placeholder shown while no device has been configured yet. */
@Composable
internal fun DevicesEmptyCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(stringResource(R.string.settings_devices_empty))
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.settings_devices_empty_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
