package io.github.mvolkert.entryrecorder.ui.recordings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.domain.model.Device
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.ui.components.filterChipLeadingIcon
import io.github.mvolkert.entryrecorder.ui.theme.Spacing
import io.github.mvolkert.entryrecorder.ui.theme.eventTypeIcon

private val EVENT_FILTER_OPTIONS: List<Pair<EventType?, Int>> = listOf(
    null to R.string.recordings_filter_all_events,
    EventType.RING to R.string.recordings_filter_ring,
    EventType.MOTION to R.string.recordings_filter_motion,
    EventType.NOISE to R.string.recordings_filter_noise,
    EventType.MANUAL to R.string.recordings_filter_manual,
)

/**
 * The event-type and device filter rows of the Recordings screen. The picked chip steps up to the
 * emphasized label role — weight from the type scale, never a raw `fontWeight` — and the selection
 * ticks, so the filter reads as the playful moment the tab is allowed.
 *
 * The query is not here: search is a [RecordingsSearchTrigger] in the band above, because the
 * state-based Material search bar owns more vertical space than a chip row can share.
 */
@Composable
internal fun RecordingsFilterBar(
    selectedEventType: EventType?,
    onEventTypeSelect: (EventType?) -> Unit,
    devices: List<Device>,
    selectedDeviceId: Long?,
    onDeviceSelect: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    Column(modifier = modifier) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            items(items = EVENT_FILTER_OPTIONS, key = { it.second }) { (eventType, labelRes) ->
                val selected = selectedEventType == eventType
                FilterChip(
                    selected = selected,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                        onEventTypeSelect(eventType)
                    },
                    label = { Text(stringResource(labelRes), style = chipLabelStyle(selected)) },
                    leadingIcon = filterChipLeadingIcon(selected, eventType?.let(::eventTypeIcon)),
                )
            }
        }

        Spacer(modifier = Modifier.height(Spacing.sm))

        if (devices.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                item {
                    val selected = selectedDeviceId == null
                    FilterChip(
                        selected = selected,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                            onDeviceSelect(null)
                        },
                        label = { Text(stringResource(R.string.recordings_filter_all_devices), style = chipLabelStyle(selected)) },
                        leadingIcon = filterChipLeadingIcon(selected),
                    )
                }
                items(devices, key = { "dev_${it.id}" }) { device ->
                    val selected = selectedDeviceId == device.id
                    FilterChip(
                        selected = selected,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                            onDeviceSelect(if (selectedDeviceId == device.id) null else device.id)
                        },
                        label = { Text(device.name, style = chipLabelStyle(selected)) },
                        leadingIcon = filterChipLeadingIcon(selected),
                    )
                }
            }
        }
    }
}

/** Chip label role: the emphasized twin carries the selection weight the spec asks of a chosen chip. */
@Composable
private fun chipLabelStyle(selected: Boolean) =
    if (selected) MaterialTheme.typography.labelLargeEmphasized else MaterialTheme.typography.labelLarge

