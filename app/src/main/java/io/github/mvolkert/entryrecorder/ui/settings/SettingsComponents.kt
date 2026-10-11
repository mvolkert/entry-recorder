package io.github.mvolkert.entryrecorder.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.ui.LocalAppSnackbar
import io.github.mvolkert.entryrecorder.ui.showMessage
import io.github.mvolkert.entryrecorder.ui.components.ExpressiveIconBadge
import io.github.mvolkert.entryrecorder.ui.theme.Spacing
import kotlinx.coroutines.flow.Flow

/**
 * The one card fill every settings surface uses. Hub rows and submenu section cards each used to pick
 * their own container colour and drifted apart, so the choice now lives here once and both read it.
 */
internal object SettingsCardDefaults {
    @Composable
    fun cardColors(): CardColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    )
}

/** Section card shell shared by every settings section (content-card shape, 16 dp inner padding). */
@Composable
internal fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = SettingsCardDefaults.cardColors(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

/**
 * Full-screen shell for a settings submenu: a top bar with a back arrow, a scrollable column and the
 * shared one-shot [events] collector (moved out of the hub so a single screen consumes the channel).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSubscreenScaffold(
    @StringRes titleRes: Int,
    onBack: () -> Unit,
    events: Flow<SettingsUiEvent>,
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val snackbarHost = LocalAppSnackbar.current
    LaunchedEffect(events) {
        events.collect { event ->
            when (event) {
                is SettingsUiEvent.Message -> snackbarHost.showMessage(event.text, event.short)
            }
        }
    }
    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        floatingActionButton = floatingActionButton,
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(titleRes), modifier = Modifier.semantics { heading() })
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.device_edit_back)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content
        )
    }
}

/**
 * Expressive hub row that navigates into a submenu: the app's one decorative icon badge, title with
 * optional subtitle, and the row morph that [ListItem] gets from its own `shapes`. No trailing
 * affordance. This is the hub's single playful lift — the submenus it opens stay Balanced.
 */
@Composable
internal fun SettingsNavRow(
    icon: ImageVector,
    @StringRes titleRes: Int,
    subtitle: String?,
    iconContainerColor: Color,
    iconContentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        leadingContent = {
            ExpressiveIconBadge(
                icon = icon,
                contentDescription = null,
                containerColor = iconContainerColor,
                contentColor = iconContentColor,
                size = 48.dp,
                iconSize = 24.dp,
            )
        },
        supportingContent = subtitle?.let {
            {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        // The hub card look: extra-rounded container in the shared settings fill. The pressed and
        // hovered shapes stay Material's, so the row morphs on touch instead of a hand-rolled scale.
        shapes = ListItemDefaults.shapes(shape = MaterialTheme.shapes.extraLarge),
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        contentPadding = PaddingValues(Spacing.lg),
    ) {
        Text(stringResource(titleRes), style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * Shared shell for every "leading label + pinned trailing control" settings row, built on the
 * expressive [ListItem]. The component measures the headline against the width the trailing control
 * leaves, so a long or localized title wraps inside the row instead of pushing a Switch or stepper
 * off the right edge, and it merges the row into one node for accessibility. [onClick] makes the whole
 * row a tap target when set.
 */
@Composable
internal fun SettingsItemRow(
    leading: @Composable ColumnScope.() -> Unit,
    trailing: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val content: @Composable () -> Unit = { Column(content = leading) }
    if (onClick == null) {
        ListItem(
            modifier = modifier.fillMaxWidth(),
            trailingContent = trailing,
            contentPadding = SettingsRowContentPadding,
            content = content,
        )
    } else {
        ListItem(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            trailingContent = trailing,
            contentPadding = SettingsRowContentPadding,
            content = content,
        )
    }
}

/**
 * Vertical padding only, set once for every settings row: the cards already supply the 16 dp
 * horizontal inset, and Material's own 16 dp start/end padding would double it.
 */
private val SettingsRowContentPadding = PaddingValues(vertical = Spacing.xs)

/**
 * Label on the left, switch on the right. The switch carries the row's state rather than being a
 * second control: it gets no callback, so [ListItem] owns the single tap target, and the role and
 * checked state move onto the merged row node — otherwise TalkBack offers "Double-tap to activate"
 * with no hint of what the tap does.
 */
@Composable
internal fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    SwitchListItem(checked = checked, onCheckedChange = onCheckedChange) {
        Text(title)
    }
}

/** Same row with a secondary hint line under the title, in the list item's own supporting slot. */
@Composable
internal fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    SwitchListItem(
        checked = checked,
        onCheckedChange = onCheckedChange,
        supportingContent = {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
    ) {
        // titleMedium already reads a step heavier than the hint under it; no raw weight needed.
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * The switch row's single implementation, shared with the device form's emphasised variant so a switch
 * never ends up with two tap targets. [content] is the headline; the row's own tap flips [checked].
 */
@Composable
internal fun SwitchListItem(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    supportingContent: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    ListItem(
        onClick = { onCheckedChange(!checked) },
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                role = Role.Switch
                toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
            },
        supportingContent = supportingContent,
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        contentPadding = SettingsRowContentPadding,
        content = content,
    )
}
