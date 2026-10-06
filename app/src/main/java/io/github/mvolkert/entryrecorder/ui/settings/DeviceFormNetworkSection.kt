package io.github.mvolkert.entryrecorder.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.data.model.DeviceType

/** Identity, addressing and HTTP credentials of the intercom. */
@Composable
internal fun DeviceFormNetworkSection(form: DeviceFormState) {
    OutlinedTextField(
        value = form.name,
        onValueChange = { form.name = it },
        label = { Text(stringResource(R.string.device_name_label)) },
        modifier = Modifier.fillMaxWidth()
    )

    FormSectionLabel(R.string.device_type_label)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FormRadioRow(
            selected = form.deviceType == DeviceType.TWO_N_VERSO,
            label = stringResource(R.string.device_type_verso),
            onClick = { form.deviceType = DeviceType.TWO_N_VERSO },
            modifier = Modifier.clickable { form.deviceType = DeviceType.TWO_N_VERSO }
        )
        FormRadioRow(
            selected = form.deviceType == DeviceType.GENERIC_RTSP_ONVIF,
            label = stringResource(R.string.device_type_generic),
            onClick = { form.deviceType = DeviceType.GENERIC_RTSP_ONVIF },
            modifier = Modifier.clickable { form.deviceType = DeviceType.GENERIC_RTSP_ONVIF }
        )
    }

    OutlinedTextField(
        value = form.ipAddress,
        onValueChange = { form.ipAddress = it },
        label = { Text(stringResource(R.string.device_ip_label)) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = form.httpPort,
            onValueChange = { form.httpPort = it },
            label = { Text(stringResource(R.string.device_http_port)) },
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        OutlinedTextField(
            value = form.rtspPort,
            onValueChange = { form.rtspPort = it },
            label = { Text(stringResource(R.string.device_rtsp_port)) },
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
    }

    FormEmphasizedSwitchRow(
        title = stringResource(R.string.device_https_toggle),
        checked = form.useHttps,
        onCheckedChange = { form.useHttps = it }
    )
    if (form.useHttps) {
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

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = form.username,
            onValueChange = { form.username = it },
            label = { Text(stringResource(R.string.device_username)) },
            modifier = Modifier.weight(1f)
        )
        PasswordTextField(
            value = form.password,
            onValueChange = { form.password = it },
            label = stringResource(R.string.device_password),
            modifier = Modifier.weight(1f)
        )
    }
}
