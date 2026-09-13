package io.github.mvolkert.entryrecorder.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.mvolkert.entryrecorder.data.device.IntercomDeviceFactory
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.data.model.DeviceType
import io.github.mvolkert.entryrecorder.data.model.SipMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceEditDialog(
    initialDevice: DeviceEntity? = null,
    onDismiss: () -> Unit,
    onSave: (DeviceEntity) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(initialDevice?.name ?: "2N IP Verso") }
    var deviceType by remember { mutableStateOf(initialDevice?.deviceType ?: DeviceType.TWO_N_VERSO) }
    var ipAddress by remember { mutableStateOf(initialDevice?.ipAddress ?: "192.168.1.100") }
    var httpPort by remember { mutableStateOf(initialDevice?.httpPort?.toString() ?: "80") }
    var rtspPort by remember { mutableStateOf(initialDevice?.rtspPort?.toString() ?: "554") }
    var rtspPath by remember { mutableStateOf(initialDevice?.rtspPath ?: "/live.sdp") }
    var username by remember { mutableStateOf(initialDevice?.username ?: "admin") }
    var password by remember { mutableStateOf(initialDevice?.password ?: "2n") }

    var sipMode by remember { mutableStateOf(initialDevice?.sipMode ?: SipMode.PEER_TO_PEER) }
    var sipLocalPort by remember { mutableStateOf(initialDevice?.sipLocalPort?.toString() ?: "5060") }
    var sipServerHost by remember { mutableStateOf(initialDevice?.sipServerHost ?: "") }
    var sipServerPort by remember { mutableStateOf(initialDevice?.sipServerPort?.toString() ?: "5060") }
    var sipUser by remember { mutableStateOf(initialDevice?.sipUser ?: "") }
    var sipPassword by remember { mutableStateOf(initialDevice?.sipPassword ?: "") }

    var recordOnMotion by remember { mutableStateOf(initialDevice?.recordOnMotion ?: true) }
    var recordOnRing by remember { mutableStateOf(initialDevice?.recordOnRing ?: true) }
    var recordOnNoise by remember { mutableStateOf(initialDevice?.recordOnNoise ?: true) }
    var recordOnMotionOnDevice by remember { mutableStateOf(initialDevice?.recordOnMotionOnDevice ?: false) }

    var isTestingConnection by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var isTestSuccess by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                Text(
                    text = if (initialDevice == null) "Add Intercom Device" else "Edit Device",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(12.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Device Name
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Device Name") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    // IP Address
                    OutlinedTextField(
                        value = ipAddress,
                        onValueChange = { ipAddress = it },
                        label = { Text("Local IP Address (2N IP Verso)") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )

                    // HTTP & RTSP Ports
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = httpPort,
                            onValueChange = { httpPort = it },
                            label = { Text("HTTP Port") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        OutlinedTextField(
                            value = rtspPort,
                            onValueChange = { rtspPort = it },
                            label = { Text("RTSP Port") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }

                    // RTSP Stream Path
                    OutlinedTextField(
                        value = rtspPath,
                        onValueChange = { rtspPath = it },
                        label = { Text("RTSP Path (e.g. /live.sdp)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Credentials
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text("Username") },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password") },
                            modifier = Modifier.weight(1f),
                            visualTransformation = PasswordVisualTransformation()
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // SIP Mode Configuration
                    Text("SIP Intercom Configuration", fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = sipMode == SipMode.PEER_TO_PEER,
                            onClick = { sipMode = SipMode.PEER_TO_PEER }
                        )
                        Text("Direct Peer-to-Peer (IP-to-IP)", modifier = Modifier.padding(end = 8.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = sipMode == SipMode.PBX_REGISTRAR,
                            onClick = { sipMode = SipMode.PBX_REGISTRAR }
                        )
                        Text("SIP PBX Server (e.g. Fritz!Box / Asterisk)")
                    }

                    if (sipMode == SipMode.PBX_REGISTRAR) {
                        OutlinedTextField(
                            value = sipServerHost,
                            onValueChange = { sipServerHost = it },
                            label = { Text("PBX Host (e.g. 192.168.1.1 or fritz.box)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = sipUser,
                                onValueChange = { sipUser = it },
                                label = { Text("SIP User") },
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = sipPassword,
                                onValueChange = { sipPassword = it },
                                label = { Text("SIP Password") },
                                modifier = Modifier.weight(1f),
                                visualTransformation = PasswordVisualTransformation()
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // Recording Triggers
                    Text("Recording Triggers", fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Record on Doorbell Ring")
                        Switch(checked = recordOnRing, onCheckedChange = { recordOnRing = it })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Record on Motion Detected")
                        Switch(checked = recordOnMotion, onCheckedChange = { recordOnMotion = it })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Record on Noise Detected")
                        Switch(checked = recordOnNoise, onCheckedChange = { recordOnNoise = it })
                    }
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Record on Motion (Analyzed On-Device by App)")
                            Switch(checked = recordOnMotionOnDevice, onCheckedChange = { recordOnMotionOnDevice = it })
                        }
                        Text(
                            "App analyzes the live video stream itself instead of relying on the device's built-in motion detection",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Test Connection Button
                    Button(
                        onClick = {
                            val candidate = DeviceEntity(
                                id = initialDevice?.id ?: 0,
                                name = name,
                                deviceType = deviceType,
                                ipAddress = ipAddress,
                                httpPort = httpPort.toIntOrNull() ?: 80,
                                rtspPort = rtspPort.toIntOrNull() ?: 554,
                                rtspPath = rtspPath,
                                username = username,
                                password = password
                            )
                            isTestingConnection = true
                            testResult = null
                            scope.launch {
                                val testDev = IntercomDeviceFactory.createDevice(candidate)
                                val res = testDev.testConnection()
                                isTestingConnection = false
                                isTestSuccess = res.isSuccess
                                testResult = if (res.isSuccess) "Connection Successful!" else "Failed: ${res.exceptionOrNull()?.localizedMessage}"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isTestingConnection
                    ) {
                        if (isTestingConnection) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text("Test Connection to 2N IP Verso")
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

                Spacer(modifier = Modifier.height(12.dp))

                // Bottom Dialog Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (name.isBlank() || ipAddress.isBlank()) {
                                Toast.makeText(context, "Please enter name and IP address", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            val updated = DeviceEntity(
                                id = initialDevice?.id ?: 0,
                                name = name.trim(),
                                deviceType = deviceType,
                                ipAddress = ipAddress.trim(),
                                httpPort = httpPort.toIntOrNull() ?: 80,
                                rtspPort = rtspPort.toIntOrNull() ?: 554,
                                rtspPath = rtspPath.trim(),
                                username = username.trim(),
                                password = password.trim(),
                                sipMode = sipMode,
                                sipLocalPort = sipLocalPort.toIntOrNull() ?: 5060,
                                sipServerHost = if (sipServerHost.isNotBlank()) sipServerHost.trim() else null,
                                sipServerPort = sipServerPort.toIntOrNull() ?: 5060,
                                sipUser = if (sipUser.isNotBlank()) sipUser.trim() else null,
                                sipPassword = if (sipPassword.isNotBlank()) sipPassword.trim() else null,
                                recordOnMotion = recordOnMotion,
                                recordOnRing = recordOnRing,
                                recordOnNoise = recordOnNoise,
                                recordOnMotionOnDevice = recordOnMotionOnDevice,
                                isEnabled = true
                            )
                            onSave(updated)
                        }
                    ) {
                        Text("Save Device")
                    }
                }
            }
        }
    }
}
