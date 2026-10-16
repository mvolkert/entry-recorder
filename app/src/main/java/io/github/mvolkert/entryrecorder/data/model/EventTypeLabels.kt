package io.github.mvolkert.entryrecorder.data.model

import androidx.annotation.StringRes
import io.github.mvolkert.entryrecorder.R

/**
 * The single place an [EventType] becomes a label resource, shared by composables and non-composable
 * callers (export gateway, share-sheet plumbing) so every surface reads identically (never the raw enum
 * name). The mapping lives outside `ui` because the data layer already owns these labels' semantics;
 * resolving the id to text stays presentation-side.
 */
@StringRes
fun eventTypeLabelRes(eventType: EventType): Int = when (eventType) {
    EventType.MOTION -> R.string.event_type_motion
    EventType.RING -> R.string.event_type_ring
    EventType.NOISE -> R.string.event_type_noise
    EventType.MANUAL -> R.string.event_type_manual
    EventType.UNKNOWN -> R.string.event_type_unknown
}
