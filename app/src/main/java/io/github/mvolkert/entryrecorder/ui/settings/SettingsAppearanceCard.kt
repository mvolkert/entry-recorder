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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import io.github.mvolkert.entryrecorder.ui.theme.accentPresetAt
import io.github.mvolkert.entryrecorder.ui.theme.accentPresets

/**
 * Accent-colour presets. A swatch themes all three color roles at once and is persisted, so the whole
 * app re-themes on pick; the dialog underneath overrides individual roles with other curated palettes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsAppearanceCard(
    settings: AppSettingsEntity,
    onSettingsChange: (AppSettingsEntity) -> Unit,
) {
    var showRoleDialog by remember { mutableStateOf(false) }
    SettingsCard {
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
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(palette.primary)
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
                            tint = palette.onPrimary
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
