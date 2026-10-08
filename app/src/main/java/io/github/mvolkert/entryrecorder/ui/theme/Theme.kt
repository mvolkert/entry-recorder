package io.github.mvolkert.entryrecorder.ui.theme

import android.app.Activity
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.data.model.MonitorStatus
import io.github.mvolkert.entryrecorder.data.model.SipMode
import io.github.mvolkert.entryrecorder.data.model.StreamProtocol

/**
 * User-selectable color mode. Stored as the enum ordinal in [io.github.mvolkert.entryrecorder.data.local.entity.AppSettingsEntity.themeMode];
 * [System] follows the device setting, while [Light]/[Dark] force one regardless of it.
 * MainActivity additionally pins the framework night mode to this choice, so the system splash
 * and window background resolve the matching day/night resources.
 */
enum class ThemeMode { System, Light, Dark }

/** The [ThemeMode] at [ordinal], falling back to [ThemeMode.System] for a stale or out-of-range value. */
fun themeModeAt(ordinal: Int): ThemeMode = ThemeMode.values().getOrNull(ordinal) ?: ThemeMode.System

/** The resolved dark/light decision of the enclosing [AppTheme], for widgets that theme themselves per mode. */
val LocalDarkTheme = staticCompositionLocalOf { true }

/**
 * The eight visible accent roles for one color mode. Each pairing is derived so foreground vs
 * background contrast stays at or above WCAG AA (4.5:1), so users cannot pick a broken combination
 * the way a free color wheel would allow.
 */
data class AccentRoles(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val tertiary: Color,
    val onTertiary: Color,
)

/** A curated accent preset: explicit, independently derived role sets for the dark and light schemes. */
data class AccentPalette(
    @StringRes val labelRes: Int,
    val dark: AccentRoles,
    val light: AccentRoles,
)

// Chroma-first presets, derived in OKLCh (not picked by eye): each primary and primaryContainer takes
// ~90% of the chroma the sRGB gamut allows at its own lightness, on the **same hue** as the previous
// baseline set — the old values were Material 3 tone-80 presets, which are low-chroma by construction.
// Dark primaries sit at OKLCh L≈0.76 so they clear 7:1 against the dark surface (`primary` doubles as
// the text/icon color of section headers, buttons, switches). Light roles are NOT a swap of the dark
// pair: they are derived independently (same hues, darker primaries that clear 4.5:1 BOTH as text on
// the light surface and under white button text) by `tools/derive_accents.py`, which prints these
// literals and asserts every pair. Do not hand-edit a single role: re-run the script, otherwise the
// accent silently becomes unreadable at one size or the other. `AccentPaletteContrastTest` re-checks
// all pairs on every test run.
val accentPresets: List<AccentPalette> = listOf(
    AccentPalette(
        labelRes = R.string.accent_default,
        dark = AccentRoles(
            primary = Color(0xFFBB9FF8), onPrimary = Color(0xFF2B1E42),
            primaryContainer = Color(0xFF7322CC), onPrimaryContainer = Color(0xFFE8E6EF),
            secondary = Color(0xFFC1ACE0), onSecondary = Color(0xFF2D1E40),
            tertiary = Color(0xFFEF9BB5), onTertiary = Color(0xFF3A1B26),
        ),
        light = AccentRoles(
            primary = Color(0xFF8027E3), onPrimary = Color(0xFFF8F5FA),
            primaryContainer = Color(0xFFE2D7FF), onPrimaryContainer = Color(0xFF332350),
            secondary = Color(0xFF8359B6), onSecondary = Color(0xFFF8F5FA),
            tertiary = Color(0xFFB72165), onTertiary = Color(0xFFF8F5FA),
        ),
    ),
    AccentPalette(
        labelRes = R.string.accent_teal,
        dark = AccentRoles(
            primary = Color(0xFF39C7D1), onPrimary = Color(0xFF1A2A2B),
            primaryContainer = Color(0xFF1A6A70), onPrimaryContainer = Color(0xFFD6EDEF),
            secondary = Color(0xFF7DC6CC), onSecondary = Color(0xFF1A2A2B),
            tertiary = Color(0xFF9EB7EE), onTertiary = Color(0xFF12234F),
        ),
        light = AccentRoles(
            primary = Color(0xFF1E767D), onPrimary = Color(0xFFF8F5FA),
            primaryContainer = Color(0xFF87F2FA), onPrimaryContainer = Color(0xFF043539),
            secondary = Color(0xFF4C7477), onSecondary = Color(0xFFF8F5FA),
            tertiary = Color(0xFF2552F0), onTertiary = Color(0xFFF8F5FA),
        ),
    ),
    AccentPalette(
        labelRes = R.string.accent_amber,
        dark = AccentRoles(
            primary = Color(0xFFD9A932), onPrimary = Color(0xFF2C2618),
            primaryContainer = Color(0xFF745916), onPrimaryContainer = Color(0xFFEFE7D7),
            secondary = Color(0xFFCFB475), onSecondary = Color(0xFF2C2618),
            tertiary = Color(0xFFD2A1EE), onTertiary = Color(0xFF311D3B),
        ),
        light = AccentRoles(
            primary = Color(0xFF826419), onPrimary = Color(0xFFF8F5FA),
            primaryContainer = Color(0xFFFCDA90), onPrimaryContainer = Color(0xFF3C2B04),
            secondary = Color(0xFF7D6D49), onSecondary = Color(0xFFF8F5FA),
            tertiary = Color(0xFF9523C5), onTertiary = Color(0xFFF8F5FA),
        ),
    ),
    AccentPalette(
        labelRes = R.string.accent_magenta,
        dark = AccentRoles(
            primary = Color(0xFFF884C2), onPrimary = Color(0xFF381B2B),
            primaryContainer = Color(0xFFA01C6E), onPrimaryContainer = Color(0xFFEFE5E9),
            secondary = Color(0xFFE2A2BB), onSecondary = Color(0xFF391B28),
            tertiary = Color(0xFFDDB055), onTertiary = Color(0xFF2D2518),
        ),
        light = AccentRoles(
            primary = Color(0xFFB3207C), onPrimary = Color(0xFFF8F5FA),
            primaryContainer = Color(0xFFFFCEE5), onPrimaryContainer = Color(0xFF491A35),
            secondary = Color(0xFFA35376), onSecondary = Color(0xFFF8F5FA),
            tertiary = Color(0xFF846319), onTertiary = Color(0xFFF8F5FA),
        ),
    ),
    AccentPalette(
        labelRes = R.string.accent_blue,
        dark = AccentRoles(
            primary = Color(0xFF8BB1F7), onPrimary = Color(0xFF162543),
            primaryContainer = Color(0xFF114ED2), onPrimaryContainer = Color(0xFFE4E8EF),
            secondary = Color(0xFF9DBADF), onSecondary = Color(0xFF192739),
            tertiary = Color(0xFFC6A7EE), onTertiary = Color(0xFF2D1D3F),
        ),
        light = AccentRoles(
            primary = Color(0xFF145AE5), onPrimary = Color(0xFFF8F5FA),
            primaryContainer = Color(0xFFCDDFFF), onPrimaryContainer = Color(0xFF172C55),
            secondary = Color(0xFF4A71A1), onSecondary = Color(0xFFF8F5FA),
            tertiary = Color(0xFF8A25D7), onTertiary = Color(0xFFF8F5FA),
        ),
    ),
    AccentPalette(
        labelRes = R.string.accent_green,
        dark = AccentRoles(
            primary = Color(0xFF5ECF33), onPrimary = Color(0xFF1D2B19),
            primaryContainer = Color(0xFF2F6F17), onPrimaryContainer = Color(0xFFDAEFD4),
            secondary = Color(0xFF94C976), onSecondary = Color(0xFF1F2A19),
            tertiary = Color(0xFF5ECAD3), onTertiary = Color(0xFF1A2A2B),
        ),
        light = AccentRoles(
            primary = Color(0xFF357C1A), onPrimary = Color(0xFFF8F5FA),
            primaryContainer = Color(0xFFBDEFAE), onPrimaryContainer = Color(0xFF16370A),
            secondary = Color(0xFF587647), onSecondary = Color(0xFFF8F5FA),
            tertiary = Color(0xFF1E767D), onTertiary = Color(0xFFF8F5FA),
        ),
    ),
    AccentPalette(
        labelRes = R.string.accent_coral,
        dark = AccentRoles(
            primary = Color(0xFFF89082), onPrimary = Color(0xFF3B1C18),
            primaryContainer = Color(0xFFAB1E19), onPrimaryContainer = Color(0xFFEFE5E4),
            secondary = Color(0xFFE1A79D), onSecondary = Color(0xFF3B1C18),
            tertiary = Color(0xFFEF9ABA), onTertiary = Color(0xFF391B27),
        ),
        light = AccentRoles(
            primary = Color(0xFFBF221C), onPrimary = Color(0xFFF8F5FA),
            primaryContainer = Color(0xFFFFD2CB), onPrimaryContainer = Color(0xFF4F1A15),
            secondary = Color(0xFFA9564A), onSecondary = Color(0xFFF8F5FA),
            tertiary = Color(0xFFB6206D), onTertiary = Color(0xFFF8F5FA),
        ),
    ),
    AccentPalette(
        labelRes = R.string.accent_violet,
        dark = AccentRoles(
            primary = Color(0xFFB1A3F8), onPrimary = Color(0xFF271F45),
            primaryContainer = Color(0xFF6624D9), onPrimaryContainer = Color(0xFFE7E7EF),
            secondary = Color(0xFFBAAFE0), onSecondary = Color(0xFF291E43),
            tertiary = Color(0xFFEF95D2), onTertiary = Color(0xFF371B2E),
        ),
        light = AccentRoles(
            primary = Color(0xFF7329F0), onPrimary = Color(0xFFF8F5FA),
            primaryContainer = Color(0xFFDDD9FF), onPrimaryContainer = Color(0xFF2F2552),
            secondary = Color(0xFF795DBA), onSecondary = Color(0xFFF8F5FA),
            tertiary = Color(0xFFAE208D), onTertiary = Color(0xFFF8F5FA),
        ),
    ),
)

// The light scheme's neutral tokens are set explicitly instead of resting on the
// lightColorScheme() defaults, so cards and list rows keep a predictable contrast against the
// light surface. The dark scheme keeps the Material 3 defaults (already contrast-checked).
private val LightNeutralBackground = Color(0xFFFFFBFE)
private val LightNeutralOnBackground = Color(0xFF1C1B1F)
private val LightNeutralSurface = Color(0xFFFFFBFE)
private val LightNeutralOnSurface = Color(0xFF1C1B1F)
private val LightNeutralSurfaceVariant = Color(0xFFE7E0EC)
private val LightNeutralOnSurfaceVariant = Color(0xFF49454F)
private val LightNeutralOutline = Color(0xFF79747E)
private val LightNeutralOutlineVariant = Color(0xFFCAC4D0)

internal fun buildColorScheme(
    primary: AccentPalette,
    secondary: AccentPalette,
    tertiary: AccentPalette,
    dark: Boolean,
): ColorScheme = if (dark) {
    darkColorScheme(
        primary = primary.dark.primary,
        onPrimary = primary.dark.onPrimary,
        primaryContainer = primary.dark.primaryContainer,
        onPrimaryContainer = primary.dark.onPrimaryContainer,
        secondary = secondary.dark.secondary,
        onSecondary = secondary.dark.onSecondary,
        // Each preset derives exactly one container pair (its primary's). Reusing that pair for the role's
        // own container keeps the nav active indicator, the FilterChips and the selected recording card on
        // the chosen palette instead of the neutral Material default: same hue family, and the
        // container/on-container contrast is already the measured one.
        secondaryContainer = secondary.dark.primaryContainer,
        onSecondaryContainer = secondary.dark.onPrimaryContainer,
        tertiary = tertiary.dark.tertiary,
        onTertiary = tertiary.dark.onTertiary,
        tertiaryContainer = tertiary.dark.primaryContainer,
        onTertiaryContainer = tertiary.dark.onPrimaryContainer,
    )
} else {
    lightColorScheme(
        primary = primary.light.primary,
        onPrimary = primary.light.onPrimary,
        primaryContainer = primary.light.primaryContainer,
        onPrimaryContainer = primary.light.onPrimaryContainer,
        secondary = secondary.light.secondary,
        onSecondary = secondary.light.onSecondary,
        secondaryContainer = secondary.light.primaryContainer,
        onSecondaryContainer = secondary.light.onPrimaryContainer,
        tertiary = tertiary.light.tertiary,
        onTertiary = tertiary.light.onTertiary,
        tertiaryContainer = tertiary.light.primaryContainer,
        onTertiaryContainer = tertiary.light.onPrimaryContainer,
        background = LightNeutralBackground,
        onBackground = LightNeutralOnBackground,
        surface = LightNeutralSurface,
        onSurface = LightNeutralOnSurface,
        surfaceVariant = LightNeutralSurfaceVariant,
        onSurfaceVariant = LightNeutralOnSurfaceVariant,
        outline = LightNeutralOutline,
        outlineVariant = LightNeutralOutlineVariant,
    )
}

/** The preset at [index], falling back to the first one for stale or out-of-range stored indices. */
fun accentPresetAt(index: Int): AccentPalette = accentPresets.getOrNull(index) ?: accentPresets.first()

/**
 * A fixed, contrast-checked status color pair. [container] is safe as a filled badge background and
 * also clears 4.5:1 on the black video scrim, so the same hue reads as header text; [onContainer] is
 * the dark foreground for the filled form. These hues are deliberately independent of the user accent
 * so a doorbell, a motion clip and a live REC look the same on every screen (StatusPaletteContrastTest
 * re-checks every pairing on each test run).
 */
data class StatusColor(val container: Color, val onContainer: Color)

private val statusRing = StatusColor(Color(0xFFFFB74D), Color(0xFF4A2B00))
private val statusMotion = StatusColor(Color(0xFF4FC3F7), Color(0xFF00293B))
private val statusNoise = StatusColor(Color(0xFFCE93D8), Color(0xFF33103A))
private val statusManual = StatusColor(Color(0xFF81C784), Color(0xFF0F2E12))

/** The single trigger-badge palette, replacing the two divergent inline EventType maps. */
fun statusColorFor(eventType: EventType): StatusColor = when (eventType) {
    EventType.RING -> statusRing
    EventType.MOTION -> statusMotion
    EventType.NOISE -> statusNoise
    EventType.MANUAL -> statusManual
}

/** Badge fill for a trigger type; also readable as on-scrim header text. */
fun eventTypeColor(eventType: EventType): Color = statusColorFor(eventType).container

/** Dark foreground to pair with [eventTypeColor] on a filled badge. */
fun eventTypeOnColor(eventType: EventType): Color = statusColorFor(eventType).onContainer

/** Live-recording badge/dot color and its high-contrast foreground. */
val recordingStatusColor: Color = Color(0xFFD32F2F)
val onRecordingStatusColor: Color = Color(0xFFFFFFFF)

/** Amber shared by the "motion right now" walk indicator and a degraded monitor dot. */
val statusWarnColor: Color = Color(0xFFF9A825)

private val statusMonitorHealthy = Color(0xFF43A047)
private val statusMonitorOffline = Color(0xFFE53935)

/**
 * Live-monitoring dot color. DISABLED fades to a translucent onSurfaceVariant, so it tracks the scheme
 * while the healthy/degraded/offline dots keep their traffic-light semantics independent of the accent.
 */
@Composable
fun monitorStatusColor(status: MonitorStatus): Color = when (status) {
    MonitorStatus.DISABLED -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    MonitorStatus.MONITORING, MonitorStatus.MOTION -> statusMonitorHealthy
    MonitorStatus.DEGRADED -> statusWarnColor
    MonitorStatus.OFFLINE -> statusMonitorOffline
}

/** Opaque black that pillarboxes video whose aspect ratio differs from its container. */
val VideoScrim: Color = Color.Black

/** Foreground drawn on the dark video scrim / letterbox, provided once via LocalContentColor. */
val onScrimColor: Color = Color.White

/**
 * App spacing scale. Material 3 ships no spacing tokens, so layout gaps and container padding name a
 * step here rather than a bare dp value. The steps are chosen so the values already in the layouts map
 * one-to-one (xs 4, sm 8, md 12, lg 16, xl 24); genuinely tight internal metrics (badge/pill interiors)
 * stay raw because a named step would round them up and reflow the row.
 */
object Spacing {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
}

/**
 * Localized label for a trigger badge — the single place an [EventType] becomes UI text, shared by the
 * recording list and the call header so both read identically (never the raw enum name). The resource
 * id is exposed too so non-composable callers (e.g. the share-sheet ViewModel) resolve the same string.
 */
@StringRes
fun eventTypeLabelRes(eventType: EventType): Int = when (eventType) {
    EventType.MOTION -> R.string.event_type_motion
    EventType.RING -> R.string.event_type_ring
    EventType.NOISE -> R.string.event_type_noise
    EventType.MANUAL -> R.string.event_type_manual
}

@Composable
fun eventTypeLabel(eventType: EventType): String = stringResource(eventTypeLabelRes(eventType))

/** Localized SIP operating-mode label for a device summary line. */
@Composable
fun sipModeLabel(mode: SipMode): String = stringResource(
    when (mode) {
        SipMode.PEER_TO_PEER -> R.string.sip_mode_peer_to_peer
        SipMode.PBX_REGISTRAR -> R.string.sip_mode_pbx_registrar
        SipMode.DISABLED -> R.string.sip_mode_disabled
    }
)

/** Localized transport label for the live-stream protocol badge. */
@Composable
fun streamProtocolLabel(protocol: StreamProtocol): String = stringResource(
    when (protocol) {
        StreamProtocol.AUTO -> R.string.stream_protocol_auto
        StreamProtocol.RTSP -> R.string.stream_protocol_rtsp
        StreamProtocol.MJPEG_STREAM -> R.string.stream_protocol_mjpeg
        StreamProtocol.HTTP_SNAPSHOT -> R.string.stream_protocol_snapshot
    }
)

/**
 * Applies the persisted per-role accent presets. Every role takes its own preset's color plus that
 * preset's on- and container pair, so any combination of the curated list stays contrast-checked and
 * visibly themes the container-based components (nav indicator, filter chips, selected card).
 *
 * When [useDynamicColor] is true and the platform supports it (Android 12+/API 31+), the curated
 * presets are bypassed and the whole scheme comes from `dynamic{Light,Dark}ColorScheme(context)`.
 * Older API levels silently fall through to the curated accents so the setting is inert instead of
 * crashing.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AppTheme(
    themeMode: Int,
    primaryIndex: Int,
    secondaryIndex: Int,
    tertiaryIndex: Int,
    useDynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeModeAt(themeMode)) {
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
        ThemeMode.System -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val dynamicAvailable = useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme = if (dynamicAvailable) {
        remember(dark) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
    } else {
        remember(primaryIndex, secondaryIndex, tertiaryIndex, dark) {
            buildColorScheme(
                primary = accentPresetAt(primaryIndex),
                secondary = accentPresetAt(secondaryIndex),
                tertiary = accentPresetAt(tertiaryIndex),
                dark = dark,
            )
        }
    }

    // Edge-to-edge bars are transparent, so icon contrast is the only thing that must follow the
    // resolved scheme; MainActivity's uiMode override keeps the framework side in agreement.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
    ) {
        CompositionLocalProvider(
            LocalDarkTheme provides dark,
            content = content,
        )
    }
}
