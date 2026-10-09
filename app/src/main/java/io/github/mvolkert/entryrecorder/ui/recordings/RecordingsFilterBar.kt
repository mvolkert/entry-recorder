package io.github.mvolkert.entryrecorder.ui.recordings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.ui.theme.Spacing

private val EVENT_FILTER_OPTIONS: List<Pair<EventType?, Int>> = listOf(
    null to R.string.recordings_filter_all_events,
    EventType.RING to R.string.recordings_filter_ring,
    EventType.MOTION to R.string.recordings_filter_motion,
    EventType.NOISE to R.string.recordings_filter_noise,
    EventType.MANUAL to R.string.recordings_filter_manual,
)

/** Search field plus the event-type and device filter rows of the Recordings screen. */
@Composable
internal fun RecordingsFilterBar(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    selectedEventType: EventType?,
    onEventTypeSelect: (EventType?) -> Unit,
    devices: List<DeviceEntity>,
    selectedDeviceId: Long?,
    onDeviceSelect: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.recordings_search_placeholder)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = stringResource(R.string.recordings_cd_search)) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.recordings_cd_clear))
                    }
                }
            },
            singleLine = true,
            // Search pill: beta01's Shapes has no `full` token, so CircleShape (like StatusChip).
            shape = CircleShape
        )

        Spacer(modifier = Modifier.height(Spacing.sm))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            items(items = EVENT_FILTER_OPTIONS, key = { it.second }) { (eventType, labelRes) ->
                FilterChip(
                    selected = selectedEventType == eventType,
                    onClick = { onEventTypeSelect(eventType) },
                    label = { Text(stringResource(labelRes)) }
                )
            }
        }

        Spacer(modifier = Modifier.height(Spacing.sm))

        if (devices.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                item {
                    FilterChip(
                        selected = selectedDeviceId == null,
                        onClick = { onDeviceSelect(null) },
                        label = { Text(stringResource(R.string.recordings_filter_all_devices)) }
                    )
                }
                items(devices, key = { "dev_${it.id}" }) { device ->
                    FilterChip(
                        selected = selectedDeviceId == device.id,
                        onClick = {
                            onDeviceSelect(if (selectedDeviceId == device.id) null else device.id)
                        },
                        label = { Text(device.name) },
                        leadingIcon = if (selectedDeviceId == device.id) {
                            { Icon(Icons.Default.Check, contentDescription = null) }
                        } else null
                    )
                }
            }
        }
    }
}
