package io.github.mvolkert.entryrecorder.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.data.model.SipMode
import io.github.mvolkert.entryrecorder.sip.SipCallTiming
import io.github.mvolkert.entryrecorder.sip.SipMissingField
import io.github.mvolkert.entryrecorder.sip.SipProbeResult
import io.github.mvolkert.entryrecorder.ui.components.MorphingIcon

/**
 * Probes the device with the values currently typed in, before anything is saved. This section holds no
 * state of its own: the probe lives in [SettingsViewModel] so that turning the phone over while one is
 * running keeps the answer instead of losing both the result and the in-flight request.
 *
 * Two independent probes live here: the HTTP one asks the intercom itself, the SIP one asks a registrar
 * whether the typed account would register. The latter runs on a throw-away Linphone core, so testing
 * never reconfigures the registration the monitor service is holding.
 */
@Composable
internal fun DeviceFormTestSection(
    form: DeviceFormState,
    connectionTest: DeviceTestState,
    sipTest: SipTestState,
    monitorUsesSip: Boolean,
    onTestConnection: () -> Unit,
    onTestSipRegistration: () -> Unit,
) {
    val connectionRunning = connectionTest is DeviceTestState.Running

    Button(
        onClick = onTestConnection,
        modifier = Modifier.fillMaxWidth(),
        enabled = !connectionRunning
    ) {
        if (connectionRunning) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(stringResource(R.string.device_test_connection))
    }

    when (connectionTest) {
        DeviceTestState.Idle, DeviceTestState.Running -> Unit
        DeviceTestState.Passed -> TestResultRow(
            stringResource(R.string.device_test_success),
            success = true
        )

        is DeviceTestState.Failed -> TestResultRow(
            stringResource(R.string.device_test_failed, connectionTest.reason ?: ""),
            success = false
        )
    }

    // Peer-to-peer has no registrar to ask, so the probe only exists for the PBX case.
    if (form.sipMode == SipMode.PBX_REGISTRAR) {
        OutlinedButton(
            onClick = onTestSipRegistration,
            modifier = Modifier.fillMaxWidth(),
            enabled = sipTest !is SipTestState.Running && !connectionRunning
        ) {
            if (sipTest is SipTestState.Running) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(stringResource(R.string.device_sip_test_button))
        }

        if (monitorUsesSip) {
            Text(
                text = stringResource(R.string.device_sip_test_monitoring_caution),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        when (sipTest) {
            SipTestState.Idle, SipTestState.Running -> Unit
            is SipTestState.Completed -> SipTestResultRow(sipTest.result)
        }
    }
}

/** Icon plus explanation under a test button; both probes report their outcome the same way. */
@Composable
private fun TestResultRow(message: String, success: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        MorphingIcon(
            imageVector = if (success) Icons.Default.CheckCircle else Icons.Default.Error,
            contentDescription = null,
            tint = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        Text(
            text = message,
            color = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/**
 * One registrar-probe outcome. The four arms are four different facts about the typed account, so they
 * stay four separate strings rather than one summary line.
 */
@Composable
private fun SipTestResultRow(result: SipProbeResult) {
    when (result) {
        is SipProbeResult.Registered -> TestResultRow(
            stringResource(R.string.device_sip_test_success, result.server),
            success = true
        )

        is SipProbeResult.Rejected -> TestResultRow(
            stringResource(R.string.device_sip_test_failed, result.reason),
            success = false
        )

        is SipProbeResult.NoAnswer -> {
            // The last state is what separates "the registrar ignored us" from "nothing was
            // ever sent", which are different bugs on different sides of the LAN.
            val lastState = result.observed
                ?: stringResource(R.string.device_sip_test_no_state)
            TestResultRow(
                stringResource(
                    R.string.device_sip_test_no_answer,
                    result.server,
                    SipCallTiming.SIP_PROBE_TIMEOUT_MS.toInt() / 1000,
                    lastState
                ),
                success = false
            )
        }

        is SipProbeResult.MissingFields -> TestResultRow(
            stringResource(
                when (result.field) {
                    SipMissingField.CALLING_MODE ->
                        R.string.device_sip_test_missing_calling_mode

                    SipMissingField.PBX_HOST ->
                        R.string.device_sip_test_missing_pbx_host

                    SipMissingField.SIP_USER ->
                        R.string.device_sip_test_missing_sip_user
                }
            ),
            success = false
        )
    }
}
