package io.github.mvolkert.entryrecorder.ui.incoming

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.ui.theme.eventTypeColor
import io.github.mvolkert.entryrecorder.ui.theme.onScrimColor

/**
 * Top overlay of the incoming-call screen: why the screen opened, which device triggered it and the
 * dismiss action. Sits under the status bar inset because the activity draws edge-to-edge.
 * [caller] (who/what rang, e.g. the SIP peer or "Doorbell Button") only enriches a ring; motion and noise
 * previews show the device alone.
 */
@Composable
internal fun IncomingCallHeader(
    eventType: EventType,
    deviceName: String?,
    caller: String?,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = 8.dp, start = 16.dp, end = 16.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.65f),
        contentColor = onScrimColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                val headerText = stringResource(
                    when (eventType) {
                        EventType.RING -> R.string.incoming_header_ring
                        EventType.MOTION -> R.string.incoming_header_motion
                        EventType.NOISE -> R.string.incoming_header_noise
                        EventType.MANUAL -> R.string.incoming_header_manual
                    }
                )
                Text(
                    text = headerText,
                    color = eventTypeColor(eventType),
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = listOfNotNull(
                        deviceName,
                        caller?.takeIf { it.isNotBlank() && eventType == EventType.RING }
                    ).joinToString(" \u00B7 ")
                        .ifBlank { stringResource(R.string.incoming_device_fallback) },
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.incoming_cd_dismiss)
                )
            }
        }
    }
}
