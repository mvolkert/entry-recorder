package io.github.mvolkert.entryrecorder.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.data.local.entity.AppSettingsEntity
import io.github.mvolkert.entryrecorder.ui.theme.LocalDarkTheme
import io.github.mvolkert.entryrecorder.ui.theme.ThemeMode
import io.github.mvolkert.entryrecorder.ui.theme.accentPresetAt
import io.github.mvolkert.entryrecorder.ui.theme.accentPresets

/**
 * Appearance settings: the light/dark/system color mode and the accent-colour presets. The mode drives
 * which [androidx.compose.material3.ColorScheme] variant the app builds; a swatch themes all three color
 * roles at once and is persisted, so the whole app re-themes on pick; the dialog underneath overrides
 * individual roles with other curated palettes.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsAppearanceCard(
    settings: AppSettingsEntity,
    onSettingsChange: (AppSettingsEntity) -> Unit,
) {
    var showRoleDialog by remember { mutableStateOf(false) }
    SettingsCard {
        Text(
            text = stringResource(R.string.settings_theme_mode_label),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val modes = ThemeMode.values()
            modes.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = settings.themeMode == index,
                    onClick = { onSettingsChange(settings.copy(themeMode = index)) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                ) {
                    Text(
                        text = stringResource(
                            when (mode) {
                                ThemeMode.System -> R.string.settings_theme_mode_system
                                ThemeMode.Light -> R.string.settings_theme_mode_light
                                ThemeMode.Dark -> R.string.settings_theme_mode_dark
                            }
                        )
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.settings_theme_mode_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.settings_accent_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            accentPresets.forEachIndexed { index, palette ->
                val selected = settings.themePrimaryIndex == index
                val label = stringResource(palette.labelRes)
                val roles = if (LocalDarkTheme.current) palette.dark else palette.light
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(roles.primary)
                        .border(
                            width = if (selected) 3.dp else 0.dp,
                            color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                            shape = CircleShape
                        )
                        // One tap themes every role, which also clears any per-role override.
                        .clickable {
                            onSettingsChange(
                                settings.copy(
                                    themePrimaryIndex = index,
                                    themeSecondaryIndex = index,
                                    themeTertiaryIndex = index,
                                )
                            )
                        }
                        .semantics { contentDescription = label },
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = stringResource(R.string.settings_accent_selected),
                            tint = roles.onPrimary
                        )
                    }
                }
            }
        }
        Text(
            text = stringResource(
                R.string.settings_accent_roles_summary,
                stringResource(accentPresetAt(settings.themePrimaryIndex).labelRes),
                stringResource(accentPresetAt(settings.themeSecondaryIndex).labelRes),
                stringResource(accentPresetAt(settings.themeTertiaryIndex).labelRes),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = { showRoleDialog = true }) {
            Text(stringResource(R.string.settings_accent_customize))
        }
    }

    if (showRoleDialog) {
        AccentRolePickerDialog(
            settings = settings,
            onSettingsChange = onSettingsChange,
            onDismiss = { showRoleDialog = false },
        )
    }
}
