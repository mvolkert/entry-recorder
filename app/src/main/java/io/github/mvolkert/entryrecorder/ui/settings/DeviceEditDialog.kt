package io.github.mvolkert.entryrecorder.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.data.device.IntercomDeviceFactory
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.data.model.DeviceType
import io.github.mvolkert.entryrecorder.data.model.SipMode
import io.github.mvolkert.entryrecorder.data.model.StreamProtocol
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceEditDialog(
    initialDevice: DeviceEntity? = null,
    onDismiss: () -> Unit,
    onSave: (DeviceEntity) -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(initialDevice?.name ?: resources.getString(R.string.device_default_name)) }
    var deviceType by remember { mutableStateOf(initialDevice?.deviceType ?: DeviceType.TWO_N_VERSO) }
    var ipAddress by remember { mutableStateOf(initialDevice?.ipAddress ?: "192.168.1.100") }
    var httpPort by remember { mutableStateOf(initialDevice?.httpPort?.toString() ?: "80") }
    var rtspPort by remember { mutableStateOf(initialDevice?.rtspPort?.toString() ?: "554") }
    var rtspPath by remember { mutableStateOf(initialDevice?.rtspPath ?: "/live.sdp") }
    var username by remember { mutableStateOf(initialDevice?.username ?: "admin") }
    var password by remember { mutableStateOf(initialDevice?.password ?: "2n") }

    var streamProtocol by remember { mutableStateOf(initialDevice?.streamProtocol ?: StreamProtocol.AUTO) }
    var mjpegPath by remember { mutableStateOf(initialDevice?.mjpegPath ?: "/api/camera/mjpeg") }
    var snapshotPath by remember { mutableStateOf(initialDevice?.snapshotPath ?: "/api/camera/snapshot") }
    var snapshotFps by remember { mutableStateOf(initialDevice?.snapshotFps?.toString() ?: "5") }

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

    // Network / HTTPS
    var useHttps by remember { mutableStateOf(initialDevice?.useHttps ?: false) }
    var httpsPort by remember { mutableStateOf(initialDevice?.httpsPort?.toString() ?: "443") }

    // Per-event recording durations
    var ringRecordSeconds by remember { mutableStateOf((initialDevice?.ringRecordSeconds ?: 60).toString()) }
    var motionPostRecordSeconds by remember { mutableStateOf((initialDevice?.motionPostRecordSeconds ?: 20).toString()) }
    var noisePostRecordSeconds by remember { mutableStateOf((initialDevice?.noisePostRecordSeconds ?: 20).toString()) }

    // Enabled toggle
    var isEnabled by remember { mutableStateOf(initialDevice?.isEnabled ?: true) }

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
                    text = stringResource(
                        if (initialDevice == null) R.string.device_add_title else R.string.device_edit_title
                    ),
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
                        label = { Text(stringResource(R.string.device_name_label)) },
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Device Type selector
                    Text(stringResource(R.string.device_type_label), fontWeight = FontWeight.Bold)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { deviceType = DeviceType.TWO_N_VERSO }
                        ) {
                            RadioButton(
                                selected = deviceType == DeviceType.TWO_N_VERSO,
                                onClick = { deviceType = DeviceType.TWO_N_VERSO }
                            )
                            Text(stringResource(R.string.device_type_verso))
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { deviceType = DeviceType.GENERIC_RTSP_ONVIF }
                        ) {
                            RadioButton(
                                selected = deviceType == DeviceType.GENERIC_RTSP_ONVIF,
                                onClick = { deviceType = DeviceType.GENERIC_RTSP_ONVIF }
                            )
                            Text(stringResource(R.string.device_type_generic))
                        }
                    }

                    // IP Address
                    OutlinedTextField(
                        value = ipAddress,
                        onValueChange = { ipAddress = it },
                        label = { Text(stringResource(R.string.device_ip_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )

                    // HTTP & RTSP Ports
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = httpPort,
                            onValueChange = { httpPort = it },
                            label = { Text(stringResource(R.string.device_http_port)) },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        OutlinedTextField(
                            value = rtspPort,
                            onValueChange = { rtspPort = it },
                            label = { Text(stringResource(R.string.device_rtsp_port)) },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }

                    // HTTPS toggle + port
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.device_https_toggle), fontWeight = FontWeight.Medium)
                        Switch(checked = useHttps, onCheckedChange = { useHttps = it })
                    }
                    if (useHttps) {
                        OutlinedTextField(
                            value = httpsPort,
                            onValueChange = { httpsPort = it },
                            label = { Text(stringResource(R.string.device_https_port)) },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }

                    // RTSP Stream Path
                    OutlinedTextField(
                        value = rtspPath,
                        onValueChange = { rtspPath = it },
                        label = { Text(stringResource(R.string.device_rtsp_path_label)) },
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Credentials
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text(stringResource(R.string.device_username)) },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text(stringResource(R.string.device_password)) },
                            modifier = Modifier.weight(1f),
                            visualTransformation = PasswordVisualTransformation()
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        thickness = DividerDefaults.Thickness,
                        color = DividerDefaults.color
                    )

                    // Video Stream Protocol Selector
                    Text(stringResource(R.string.device_protocol_label), fontWeight = FontWeight.Bold)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = streamProtocol == StreamProtocol.AUTO,
                                onClick = { streamProtocol = StreamProtocol.AUTO }
                            )
                            Text(stringResource(R.string.device_protocol_auto))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = streamProtocol == StreamProtocol.RTSP,
                                onClick = { streamProtocol = StreamProtocol.RTSP }
                            )
                            Text(stringResource(R.string.device_protocol_rtsp, rtspPort))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = streamProtocol == StreamProtocol.MJPEG_STREAM,
                                onClick = { streamProtocol = StreamProtocol.MJPEG_STREAM }
                            )
                            Text(stringResource(R.string.device_protocol_mjpeg))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = streamProtocol == StreamProtocol.HTTP_SNAPSHOT,
                                onClick = { streamProtocol = StreamProtocol.HTTP_SNAPSHOT }
                            )
                            Text(stringResource(R.string.device_protocol_snapshot))
                        }
                    }

                    if (streamProtocol == StreamProtocol.MJPEG_STREAM || streamProtocol == StreamProtocol.AUTO) {
                        OutlinedTextField(
                            value = mjpegPath,
                            onValueChange = { mjpegPath = it },
                            label = { Text(stringResource(R.string.device_mjpeg_path_label)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (streamProtocol == StreamProtocol.HTTP_SNAPSHOT || streamProtocol == StreamProtocol.AUTO) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = snapshotPath,
                                onValueChange = { snapshotPath = it },
                                label = { Text(stringResource(R.string.device_snapshot_path_label)) },
                                modifier = Modifier.weight(2f)
                            )
                            OutlinedTextField(
                                value = snapshotFps,
                                onValueChange = { snapshotFps = it },
                                label = { Text(stringResource(R.string.device_snapshot_fps)) },
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        thickness = DividerDefaults.Thickness,
                        color = DividerDefaults.color
                    )

                    // SIP Mode Configuration
                    Text(stringResource(R.string.device_sip_section), fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = sipMode == SipMode.PEER_TO_PEER,
                            onClick = { sipMode = SipMode.PEER_TO_PEER }
                        )
                        Text(stringResource(R.string.device_sip_p2p), modifier = Modifier.padding(end = 8.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = sipMode == SipMode.PBX_REGISTRAR,
                            onClick = { sipMode = SipMode.PBX_REGISTRAR }
                        )
                        Text(stringResource(R.string.device_sip_pbx))
                    }

                    if (sipMode == SipMode.PBX_REGISTRAR) {
                        OutlinedTextField(
                            value = sipServerHost,
                            onValueChange = { sipServerHost = it },
                            label = { Text(stringResource(R.string.device_sip_pbx_host)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = sipUser,
                                onValueChange = { sipUser = it },
                                label = { Text(stringResource(R.string.device_sip_user)) },
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = sipPassword,
                                onValueChange = { sipPassword = it },
                                label = { Text(stringResource(R.string.device_sip_password)) },
                                modifier = Modifier.weight(1f),
                                visualTransformation = PasswordVisualTransformation()
                            )
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        thickness = DividerDefaults.Thickness,
                        color = DividerDefaults.color
                    )

                    // Recording Triggers
                    Text(stringResource(R.string.device_triggers_section), fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.device_trigger_ring))
                        Switch(checked = recordOnRing, onCheckedChange = { recordOnRing = it })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.device_trigger_motion))
                        Switch(checked = recordOnMotion, onCheckedChange = { recordOnMotion = it })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.device_trigger_noise))
                        Switch(checked = recordOnNoise, onCheckedChange = { recordOnNoise = it })
                    }
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(stringResource(R.string.device_trigger_motion_app))
                            Switch(checked = recordOnMotionOnDevice, onCheckedChange = { recordOnMotionOnDevice = it })
                        }
                        Text(
                            stringResource(R.string.device_trigger_motion_app_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        thickness = DividerDefaults.Thickness,
                        color = DividerDefaults.color
                    )

                    // Per-event recording durations
                    Text(stringResource(R.string.device_durations_section), fontWeight = FontWeight.Bold)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = ringRecordSeconds,
                            onValueChange = { ringRecordSeconds = it },
                            label = { Text(stringResource(R.string.device_duration_ring)) },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        OutlinedTextField(
                            value = motionPostRecordSeconds,
                            onValueChange = { motionPostRecordSeconds = it },
                            label = { Text(stringResource(R.string.device_duration_motion_post)) },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        OutlinedTextField(
                            value = noisePostRecordSeconds,
                            onValueChange = { noisePostRecordSeconds = it },
                            label = { Text(stringResource(R.string.device_duration_noise_post)) },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }

                    // Device enabled toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.device_enabled_toggle), fontWeight = FontWeight.Medium)
                        Switch(checked = isEnabled, onCheckedChange = { isEnabled = it })
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
                                password = password,
                                streamProtocol = streamProtocol,
                                mjpegPath = mjpegPath,
                                snapshotPath = snapshotPath,
                                snapshotFps = snapshotFps.toIntOrNull() ?: 5
                            )
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

                Spacer(modifier = Modifier.height(12.dp))

                // Bottom Dialog Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (name.isBlank() || ipAddress.isBlank()) {
                                Toast.makeText(context, R.string.device_toast_name_ip_required, Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            // Preserve fields not exposed in this dialog (useHttps, httpsPort,
                            // ringRecordSeconds, motion/postRecord seconds, isEnabled) by starting
                            // from the existing device and only overwriting the edited fields.
                            val base = initialDevice
                                ?: DeviceEntity(name = name.trim(), ipAddress = ipAddress.trim())
                            val updated = base.copy(
                                name = name.trim(),
                                deviceType = deviceType,
                                ipAddress = ipAddress.trim(),
                                httpPort = httpPort.toIntOrNull() ?: 80,
                                useHttps = useHttps,
                                httpsPort = httpsPort.toIntOrNull() ?: 443,
                                rtspPort = rtspPort.toIntOrNull() ?: 554,
                                rtspPath = rtspPath.trim(),
                                username = username.trim(),
                                password = password.trim(),
                                streamProtocol = streamProtocol,
                                mjpegPath = mjpegPath.trim(),
                                snapshotPath = snapshotPath.trim(),
                                snapshotFps = snapshotFps.toIntOrNull() ?: 5,
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
                                ringRecordSeconds = ringRecordSeconds.toIntOrNull() ?: 60,
                                motionPostRecordSeconds = motionPostRecordSeconds.toIntOrNull() ?: 20,
                                noisePostRecordSeconds = noisePostRecordSeconds.toIntOrNull() ?: 20,
                                isEnabled = isEnabled
                            )
                            onSave(updated)
                        }
                    ) {
                        Text(stringResource(R.string.device_save))
                    }
                }
            }
        }
    }
}
