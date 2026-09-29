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
import kotlinx.coroutines.launch

/**
 * Probes the device with the values currently typed in, before anything is saved. The result is
 * local to this section: closing the dialog discards it along with the running probe.
 */
@Composable
internal fun DeviceFormTestSection(form: DeviceFormState) {
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var isTestingConnection by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var isTestSuccess by remember { mutableStateOf(false) }

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

    testResult?.let { msg ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = if (isTestSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = null,
                tint = if (isTestSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            Text(
                text = msg,
                color = if (isTestSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
