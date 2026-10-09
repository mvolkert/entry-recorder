package io.github.mvolkert.entryrecorder.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.ui.components.MorphingIcon

/** Bold group title inside the device form. */
@Composable
internal fun FormSectionLabel(@StringRes labelRes: Int) {
    Text(stringResource(labelRes), fontWeight = FontWeight.Bold)
}

@Composable
internal fun FormDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 4.dp),
        thickness = DividerDefaults.Thickness,
        color = DividerDefaults.color
    )
}

/** Radio button with its label; pass a `clickable` modifier to make the label tappable as well. */
@Composable
internal fun FormRadioRow(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

/** Title / switch row whose title carries Medium emphasis (HTTPS and the device-enabled toggle). */
@Composable
internal fun FormEmphasizedSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    SettingsItemRow(
        onClick = { onCheckedChange(!checked) },
        leading = { Text(title, fontWeight = FontWeight.Medium) },
        trailing = { Switch(checked = checked, onCheckedChange = onCheckedChange) }
    )
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
                    )
                )
            }
        }
    )
}
