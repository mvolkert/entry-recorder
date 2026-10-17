package io.github.mvolkert.entryrecorder.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.data.model.MonitorStatus

/**
 * A fixed, contrast-checked status color pair. [container] is safe as a filled badge background and
 * also clears 4.5:1 on the black video scrim, so the same hue reads as header text; [onContainer] is
 * the dark foreground for the filled form. These hues are deliberately independent of the user accent
 * so a doorbell, a motion clip and a live REC look the same on every screen (StatusPaletteContrastTest
 * re-checks every pairing on each test run).
 */
data class StatusColor(val container: Color, val onContainer: Color)

/**
 * Every fixed status hue in the app, in one place.
 *
 * These deliberately bypass [MaterialTheme]: a doorbell ring, a motion clip and a live REC are semantics,
 * not branding, so they have to read identically under whatever accent preset or dynamic scheme the user
 * picks — and the event hues also sit on the black video scrim regardless of the theme, where a scheme role
 * would be meaningless. That is an owner decision, not an oversight: StatusPaletteContrastTest re-proves
 * each container/on pairing on every run, so a hue may only be re-tuned together with that check. Nothing
 * outside this object should grow a raw status hex; name it here instead.
 */
object StatusPalette {
    val ring = StatusColor(Color(0xFFFFB74D), Color(0xFF4A2B00))
    val motion = StatusColor(Color(0xFF4FC3F7), Color(0xFF00293B))
    val noise = StatusColor(Color(0xFFCE93D8), Color(0xFF33103A))
    val manual = StatusColor(Color(0xFF81C784), Color(0xFF0F2E12))

    // A neutral blue-grey for a trigger label this app does not recognise, so an unknown row still reads as
    // a badge rather than borrowing a real trigger's hue.
    val unknown = StatusColor(Color(0xFF90A4AE), Color(0xFF263238))

    /** Live-recording badge fill and its high-contrast foreground. */
    val recording = StatusColor(Color(0xFFD32F2F), Color(0xFFFFFFFF))

    // The two steps of the FAB glyph, documented at recordingGlyphColorFor().
    val recordingGlyphLight = Color(0xFFD32F2F)
    val recordingGlyphDark = Color(0xFFEF5350)

    /** Amber shared by the "motion right now" walk indicator and a degraded monitor dot. */
    val warn = Color(0xFFF9A825)
    val monitorHealthy = Color(0xFF43A047)
    val monitorOffline = Color(0xFFE53935)
}

/** The single trigger-badge palette, replacing the two divergent inline EventType maps. */
fun statusColorFor(eventType: EventType): StatusColor = when (eventType) {
    EventType.RING -> StatusPalette.ring
    EventType.MOTION -> StatusPalette.motion
    EventType.NOISE -> StatusPalette.noise
    EventType.MANUAL -> StatusPalette.manual
    EventType.UNKNOWN -> StatusPalette.unknown
}

/** Badge fill for a trigger type; also readable as on-scrim header text. */
fun eventTypeColor(eventType: EventType): Color = statusColorFor(eventType).container

/** Dark foreground to pair with [eventTypeColor] on a filled badge. */
fun eventTypeOnColor(eventType: EventType): Color = statusColorFor(eventType).onContainer

/**
 * Glyph for a trigger type: the filter chips' leading slot and a thumbnail whose image cannot load both
 * read from here, so one trigger never shows as two different shapes. The walking figure is the same
 * symbol the live card's trigger indicators paint.
 */
fun eventTypeIcon(eventType: EventType): ImageVector = when (eventType) {
    EventType.RING -> Icons.Default.Call
    EventType.MOTION -> Icons.AutoMirrored.Filled.DirectionsWalk
    EventType.NOISE -> Icons.AutoMirrored.Filled.VolumeUp
    EventType.MANUAL, EventType.UNKNOWN -> Icons.Default.Videocam
}

/** Live-recording badge/dot color and its high-contrast foreground. */
val recordingStatusColor: Color = StatusPalette.recording.container
val onRecordingStatusColor: Color = StatusPalette.recording.onContainer

/** Amber shared by the "motion right now" walk indicator and a degraded monitor dot. */
val statusWarnColor: Color = StatusPalette.warn

/**
 * Live-monitoring dot color. DISABLED is a tint rather than a disabled component — the dot still describes
 * a real state — so it fades to [disabledContentColor] and tracks the scheme, while the
 * healthy/degraded/offline dots keep their traffic-light semantics independent of the accent.
 */
@Composable
fun monitorStatusColor(status: MonitorStatus): Color = when (status) {
    MonitorStatus.DISABLED -> disabledContentColor()
    MonitorStatus.MONITORING, MonitorStatus.MOTION -> StatusPalette.monitorHealthy
    MonitorStatus.DEGRADED -> statusWarnColor
    MonitorStatus.OFFLINE -> StatusPalette.monitorOffline
}
