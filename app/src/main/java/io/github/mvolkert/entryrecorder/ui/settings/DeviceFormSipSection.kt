package io.github.mvolkert.entryrecorder.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.data.model.SipMode

/**
 * Calling mode: dial the intercom directly or register through a PBX. The account fields belong to
 * the registrar case only — peer-to-peer needs no credentials.
 */
@Composable
internal fun DeviceFormSipSection(form: DeviceFormState) {
    FormSectionLabel(R.string.device_sip_section)
    FormRadioRow(
        selected = form.sipMode == SipMode.PEER_TO_PEER,
        label = stringResource(R.string.device_sip_p2p),
        onClick = { form.sipMode = SipMode.PEER_TO_PEER },
        modifier = Modifier.padding(end = 8.dp)
    )
    FormRadioRow(
        selected = form.sipMode == SipMode.PBX_REGISTRAR,
        label = stringResource(R.string.device_sip_pbx),
        onClick = { form.sipMode = SipMode.PBX_REGISTRAR }
    )

    if (form.sipMode == SipMode.PBX_REGISTRAR) {
        OutlinedTextField(
            value = form.sipServerHost,
            onValueChange = { form.sipServerHost = it },
            label = { Text(stringResource(R.string.device_sip_pbx_host)) },
            modifier = Modifier.fillMaxWidth()
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = form.sipUser,
                onValueChange = { form.sipUser = it },
                label = { Text(stringResource(R.string.device_sip_user)) },
                modifier = Modifier.weight(1f)
            )
            PasswordTextField(
                value = form.sipPassword,
                onValueChange = { form.sipPassword = it },
                label = stringResource(R.string.device_sip_password),
                modifier = Modifier.weight(1f)
            )
        }
    }
}
