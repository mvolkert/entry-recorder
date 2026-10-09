package io.github.mvolkert.entryrecorder.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.data.model.DeviceType

/** Identity, addressing and HTTP credentials of the intercom. */
@Composable
internal fun DeviceFormNetworkSection(form: DeviceFormState) {
    OutlinedTextField(
        value = form.name,
        onValueChange = { form.name = it },
        label = { Text(stringResource(R.string.device_name_label)) },
        modifier = Modifier.fillMaxWidth(),
        isError = form.name.isBlank(),
        supportingText = if (form.name.isBlank()) {
            { Text(stringResource(R.string.device_toast_name_ip_required), color = MaterialTheme.colorScheme.error) }
        } else null
    )

    val deviceTypes = listOf(DeviceType.TWO_N_VERSO, DeviceType.GENERIC_RTSP_ONVIF)
    FormSingleChoice(
        label = stringResource(R.string.device_type_label),
        options = deviceTypes.map {
            stringResource(
                if (it == DeviceType.TWO_N_VERSO) R.string.device_type_verso else R.string.device_type_generic
            )
        },
        selectedIndex = deviceTypes.indexOf(form.deviceType),
        onSelect = { form.deviceType = deviceTypes[it] },
    )

    OutlinedTextField(
        value = form.ipAddress,
        onValueChange = { form.ipAddress = it },
        label = { Text(stringResource(R.string.device_ip_label)) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = form.ipAddress.isBlank(),
        supportingText = if (form.ipAddress.isBlank()) {
            { Text(stringResource(R.string.device_toast_name_ip_required), color = MaterialTheme.colorScheme.error) }
        } else null
    )

    FormTwoFieldRow(
        first = { m ->
            OutlinedTextField(
                value = form.httpPort,
                onValueChange = { form.httpPort = it },
                label = { Text(stringResource(R.string.device_http_port)) },
                modifier = m,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        },
        second = { m ->
            OutlinedTextField(
                value = form.rtspPort,
                onValueChange = { form.rtspPort = it },
                label = { Text(stringResource(R.string.device_rtsp_port)) },
                modifier = m,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        },
    )

    FormEmphasizedSwitchRow(
        title = stringResource(R.string.device_https_toggle),
        checked = form.useHttps,
        onCheckedChange = { form.useHttps = it }
    )
    AnimatedVisibility(
        visible = form.useHttps,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        OutlinedTextField(
            value = form.httpsPort,
            onValueChange = { form.httpsPort = it },
            label = { Text(stringResource(R.string.device_https_port)) },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
    }

    OutlinedTextField(
        value = form.rtspPath,
        onValueChange = { form.rtspPath = it },
        label = { Text(stringResource(R.string.device_rtsp_path_label)) },
        modifier = Modifier.fillMaxWidth()
    )

    FormTwoFieldRow(
        first = { m ->
            OutlinedTextField(
                value = form.username,
                onValueChange = { form.username = it },
                label = { Text(stringResource(R.string.device_username)) },
                modifier = m
            )
        },
        second = { m ->
            PasswordTextField(
                value = form.password,
                onValueChange = { form.password = it },
                label = stringResource(R.string.device_password),
                modifier = m
            )
        },
    )
}
