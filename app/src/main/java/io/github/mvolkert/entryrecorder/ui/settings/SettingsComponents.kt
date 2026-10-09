package io.github.mvolkert.entryrecorder.ui.settings

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.mvolkert.entryrecorder.R
import kotlinx.coroutines.flow.Flow

/** Section card shell shared by every settings section (content-card shape, 16 dp inner padding). */
@Composable
internal fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium
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
    val context = LocalContext.current
    LaunchedEffect(events) {
        events.collect { event ->
            when (event) {
                is SettingsUiEvent.Message -> Toast.makeText(
                    context,
                    event.text,
                    if (event.short) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        floatingActionButton = floatingActionButton,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(titleRes)) },
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
 * Expressive hub row that navigates into a submenu: tonal icon badge, title with optional subtitle,
 * extra-rounded card and a bouncy press-scale driven by the motion scheme. No trailing affordance.
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
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>(),
        label = "settingsNavRowScale",
    )
    Card(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(interactionSource = interactionSource, indication = LocalIndication.current, onClick = onClick),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 76.dp)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(iconContainerColor, MaterialTheme.shapes.large),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconContentColor,
                )
            }
            Column(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
                Text(stringResource(titleRes), style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Shared shell for every "leading label + pinned trailing control" settings row. The leading column is
 * `weight(1f, fill = false)`, so a long (or localized) title wraps instead of pushing the trailing
 * Switch/stepper off the right edge on narrow screens, which keeps the control always visible and
 * tappable. [onClick] makes the whole row a tap target when set.
 */
@Composable
internal fun SettingsItemRow(
    leading: @Composable ColumnScope.() -> Unit,
    trailing: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
) {
    val rowModifier = modifier
        .fillMaxWidth()
        .heightIn(min = 48.dp)
        .then(
            if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, onClick = onClick)
            else Modifier
        )
    Row(
        modifier = rowModifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f, fill = false), content = leading)
        trailing()
    }
}

/** Label on the left, switch on the right. */
@Composable
internal fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    SettingsItemRow(
        onClick = { onCheckedChange(!checked) },
        leading = { Text(title) },
        trailing = { Switch(checked = checked, onCheckedChange = onCheckedChange) }
    )
}

/** Same row with a secondary hint line under the title. */
@Composable
internal fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    SettingsItemRow(
        onClick = { onCheckedChange(!checked) },
        leading = {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailing = { Switch(checked = checked, onCheckedChange = onCheckedChange) }
    )
}
