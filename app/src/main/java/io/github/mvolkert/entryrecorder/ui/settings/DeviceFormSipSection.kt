package io.github.mvolkert.entryrecorder.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
    val modes = listOf(SipMode.PEER_TO_PEER, SipMode.PBX_REGISTRAR)
    FormSingleChoice(
        label = stringResource(R.string.device_sip_section),
        options = modes.map {
            stringResource(
                if (it == SipMode.PEER_TO_PEER) R.string.device_sip_p2p else R.string.device_sip_pbx
            )
        },
        selectedIndex = modes.indexOf(form.sipMode),
        onSelect = { form.sipMode = modes[it] },
    )

    AnimatedVisibility(
        visible = form.sipMode == SipMode.PBX_REGISTRAR,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        // AnimatedVisibility gives an AnimatedVisibilityScope, not a ColumnScope, so the host field and
        // the account row below would otherwise overlap instead of stacking.
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = form.sipServerHost,
                onValueChange = { form.sipServerHost = it },
                label = { Text(stringResource(R.string.device_sip_pbx_host)) },
                modifier = Modifier.fillMaxWidth()
            )
            FormTwoFieldRow(
                first = { m ->
                    OutlinedTextField(
                        value = form.sipUser,
                        onValueChange = { form.sipUser = it },
                        label = { Text(stringResource(R.string.device_sip_user)) },
                        modifier = m
                    )
                },
                second = { m ->
                    PasswordTextField(
                        value = form.sipPassword,
                        onValueChange = { form.sipPassword = it },
                        label = stringResource(R.string.device_sip_password),
                        modifier = m
                    )
                },
            )
        }
    }
}
