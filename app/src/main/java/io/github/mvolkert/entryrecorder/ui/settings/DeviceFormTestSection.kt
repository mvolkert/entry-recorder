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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.data.device.IntercomDeviceFactory
import io.github.mvolkert.entryrecorder.data.model.SipMode
import io.github.mvolkert.entryrecorder.sip.SipCallManager
import io.github.mvolkert.entryrecorder.sip.SipCallTiming
import io.github.mvolkert.entryrecorder.sip.SipMissingField
import io.github.mvolkert.entryrecorder.sip.SipProbeResult
import kotlinx.coroutines.launch

/**
 * Probes the device with the values currently typed in, before anything is saved. The result is
 * local to this section: leaving the screen discards it along with the running probe.
 *
 * Two independent probes live here: the HTTP one asks the intercom itself, the SIP one asks a registrar
 * whether the typed account would register. The latter runs on a throw-away Linphone core, so testing
 * never reconfigures the registration the monitor service is holding.
 */
@Composable
internal fun DeviceFormTestSection(form: DeviceFormState, sipCallManager: SipCallManager) {
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var isTestingConnection by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var isTestSuccess by remember { mutableStateOf(false) }

    var isTestingSip by remember { mutableStateOf(false) }
    var sipTestResult by remember { mutableStateOf<String?>(null) }
    var isSipTestSuccess by remember { mutableStateOf(false) }

    Button(
        onClick = {
            val candidate = form.buildTestCandidate()
            isTestingConnection = true
            testResult = null
            scope.launch {
                val testDev = IntercomDeviceFactory.createDevice(candidate)
                val res = testDev.testConnection()
                isTestingConnection = false
                isTestSuccess = res.isSuccess
                testResult = if (res.isSuccess) {
                    resources.getString(R.string.device_test_success)
                } else {
                    resources.getString(
                        R.string.device_test_failed,
                        res.exceptionOrNull()?.localizedMessage ?: ""
                    )
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = !isTestingConnection
    ) {
        if (isTestingConnection) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(stringResource(R.string.device_test_connection))
    }

    testResult?.let { msg -> TestResultRow(msg, isTestSuccess) }

    // Peer-to-peer has no registrar to ask, so the probe only exists for the PBX case.
    if (form.sipMode == SipMode.PBX_REGISTRAR) {
        OutlinedButton(
            onClick = {
                val candidate = form.buildTestCandidate()
                isTestingSip = true
                sipTestResult = null
                scope.launch {
                    when (val probe = sipCallManager.probeRegistration(candidate)) {
                        is SipProbeResult.Registered -> {
                            isSipTestSuccess = true
                            sipTestResult = resources.getString(
                                R.string.device_sip_test_success,
                                probe.server
                            )
                        }

                        is SipProbeResult.Rejected -> {
                            isSipTestSuccess = false
                            sipTestResult = resources.getString(
                                R.string.device_sip_test_failed,
                                probe.reason
                            )
                        }

                        is SipProbeResult.NoAnswer -> {
                            isSipTestSuccess = false
                            // The last state is what separates "the registrar ignored us" from "nothing was
                            // ever sent", which are different bugs on different sides of the LAN.
                            val lastState = probe.observed
                                ?: resources.getString(R.string.device_sip_test_no_state)
                            sipTestResult = resources.getString(
                                R.string.device_sip_test_no_answer,
                                probe.server,
                                SipCallTiming.SIP_PROBE_TIMEOUT_MS.toInt() / 1000,
                                lastState
                            )
                        }

                        is SipProbeResult.MissingFields -> {
                            isSipTestSuccess = false
                            val messageRes = when (probe.field) {
                                SipMissingField.CALLING_MODE ->
                                    R.string.device_sip_test_missing_calling_mode

                                SipMissingField.PBX_HOST ->
                                    R.string.device_sip_test_missing_pbx_host

                                SipMissingField.SIP_USER ->
                                    R.string.device_sip_test_missing_sip_user
                            }
                            sipTestResult = resources.getString(messageRes)
                        }
                    }
                    isTestingSip = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isTestingSip && !isTestingConnection
        ) {
            if (isTestingSip) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(stringResource(R.string.device_sip_test_button))
        }

        if (sipCallManager.hasActiveCore) {
            Text(
                text = stringResource(R.string.device_sip_test_monitoring_caution),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        sipTestResult?.let { msg -> TestResultRow(msg, isSipTestSuccess) }
    }
}

/** Icon plus explanation under a test button; both probes report their outcome the same way. */
@Composable
private fun TestResultRow(message: String, success: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
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
