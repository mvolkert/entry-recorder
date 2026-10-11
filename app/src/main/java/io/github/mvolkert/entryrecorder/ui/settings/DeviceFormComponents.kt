package io.github.mvolkert.entryrecorder.ui.settings

import androidx.annotation.StringRes
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.ui.adaptive.LocalWindowInfo
import io.github.mvolkert.entryrecorder.ui.adaptive.WindowInfo
import io.github.mvolkert.entryrecorder.ui.components.MorphingIcon

/** Group title inside the device form; the role carries the weight and it is an accessibility heading. */
@Composable
internal fun FormSectionLabel(@StringRes labelRes: Int) {
    Text(
        stringResource(labelRes),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.semantics { heading() },
    )
}

/**
 * Reveal for a device-form block that a switch uncovers: alpha takes the scheme's effects spec and the
 * height change its spatial spec, so no conditional section animates on a framework default.
 */
@Composable
internal fun formRevealEnter(): EnterTransition {
    val motion = MaterialTheme.motionScheme
    return fadeIn(animationSpec = motion.defaultEffectsSpec()) +
        expandVertically(animationSpec = motion.defaultSpatialSpec())
}

/** The matching collapse of [formRevealEnter]. */
@Composable
internal fun formRevealExit(): ExitTransition {
    val motion = MaterialTheme.motionScheme
    return fadeOut(animationSpec = motion.defaultEffectsSpec()) +
        shrinkVertically(animationSpec = motion.defaultSpatialSpec())
}

/** Title / switch row whose title takes the role one step heavier than the hint rows (HTTPS, device-enabled). */
@Composable
internal fun FormEmphasizedSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    SwitchListItem(checked = checked, onCheckedChange = onCheckedChange) {
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * One-of-N selector shared by every choice group in the device form. Segmented on Medium+ when the
 * options are few and each label still fits its slice of the row within two lines; an exposed dropdown
 * on Compact, and for any group whose labels would ellipsize. The fit test measures the real text at
 * the current font and locale instead of counting characters, so it does not misfire on wide scripts.
 * Callers map the index back to their own enum.
 */
@Composable
internal fun FormSingleChoice(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val compact = LocalWindowInfo.current == WindowInfo.Compact
    if (compact || options.isEmpty()) {
        DropdownSingleChoice(label, options, selectedIndex, onSelect, modifier)
        return
    }
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // A segment shows up to two lines; only fall back to the dropdown when a label would need a
        // third line inside its share of the row (minus the button's horizontal content padding).
        val textMeasurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val perSegmentTextPx = with(density) { (maxWidth / options.size - 32.dp).toPx() }.toInt()
            .coerceAtLeast(1)
        val segmentedFriendly = options.size <= 4 && options.all {
            textMeasurer.measure(
                text = it,
                style = MaterialTheme.typography.labelLarge,
                constraints = Constraints(maxWidth = perSegmentTextPx),
            ).lineCount <= 2
        }
        if (segmentedFriendly) {
            SegmentedSingleChoice(label, options, selectedIndex, onSelect, Modifier)
        } else {
            DropdownSingleChoice(label, options, selectedIndex, onSelect, Modifier)
        }
    }
}

@Composable
private fun DropdownSingleChoice(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier,
) {
    var expanded by remember(label) { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "singleChoiceArrow",
    )
    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = options.getOrNull(selectedIndex).orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = {
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.graphicsLayer { rotationZ = arrowRotation },
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        // Transparent overlay opens the menu, so the disabled field never takes focus or a caret.
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable { expanded = true },
        )
        // The menu expresses which option the field currently holds, so its items are the selectable
        // kind; the popup only positions the surface and the group is what paints it.
        DropdownMenuPopup(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShapes()) {
                options.forEachIndexed { index, option ->
                    SelectableDropdownMenuItem(
                        selected = index == selectedIndex,
                        onClick = {
                            onSelect(index)
                            expanded = false
                        },
                        text = { Text(option) },
                        shapes = MenuDefaults.itemShape(index = index, count = options.size),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SegmentedSingleChoice(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                ) {
                    Text(
                        text = option,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Two related fields side by side on Medium+, stacked full width on Compact so their labels never get
 * ellipsized the way two `weight(1f)` columns do on a narrow screen. Each slot receives the modifier to
 * apply (weight or fill) from the container.
 */
@Composable
internal fun FormTwoFieldRow(
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit,
) {
    if (LocalWindowInfo.current == WindowInfo.Compact) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            first(Modifier.fillMaxWidth())
            second(Modifier.fillMaxWidth())
        }
    } else {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            first(Modifier.weight(1f))
            second(Modifier.weight(1f))
        }
    }
}

/**
 * Masked credential field with a reveal toggle. Exists because both device-form passwords used a bare
 * `PasswordVisualTransformation()`: a mistyped character was invisible, and the SIP half of that was
 * indistinguishable from a broken doorbell. Revealing is transient UI state, so it is not saved.
 */
@Composable
internal fun PasswordTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    var revealed by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier,
        visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { revealed = !revealed }) {
                MorphingIcon(
                    imageVector = if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = stringResource(
                        if (revealed) R.string.device_hide_password else R.string.device_show_password
                    ),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    )
}
