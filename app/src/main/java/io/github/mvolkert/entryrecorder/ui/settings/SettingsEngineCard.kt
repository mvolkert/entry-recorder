package io.github.mvolkert.entryrecorder.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.domain.alert.MotionAlertPushState
import io.github.mvolkert.entryrecorder.domain.model.AppSettings
import io.github.mvolkert.entryrecorder.data.model.RecordingMode
import io.github.mvolkert.entryrecorder.ui.LocalAppSnackbar
import io.github.mvolkert.entryrecorder.ui.components.LoadingSpinner
import io.github.mvolkert.entryrecorder.ui.showMessage
import io.github.mvolkert.entryrecorder.ui.components.filterChipLeadingIcon
import io.github.mvolkert.entryrecorder.ui.theme.Spacing
import io.github.mvolkert.entryrecorder.util.FileLoggingPlanter
import java.io.File
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Where recordings are captured: on the phone or on the Python server, plus the server endpoint
 * fields and their connection test, which only appear in server mode.
 */
@Composable
internal fun SettingsEngineCard(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    isTestingServer: Boolean,
    onTestServer: () -> Unit,
    // Receiver state for the same feature, from MotionAlertPushViewModel: this phone can be the one that gets
    // alerted as well as the one that sends, and the two are configured on different screens.
    pushState: MotionAlertPushState,
    onReceiveToggle: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    SettingsCard {
        Text(stringResource(R.string.settings_mode_question))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            FilterChip(
                selected = settings.recordingMode == RecordingMode.APP_LOCAL,
                onClick = {
                    onSettingsChange(settings.copy(recordingMode = RecordingMode.APP_LOCAL))
                },
                label = { Text(stringResource(R.string.settings_mode_local)) },
                leadingIcon = filterChipLeadingIcon(
                    settings.recordingMode == RecordingMode.APP_LOCAL,
                    Icons.Default.PhoneAndroid,
                )
            )

            FilterChip(
                selected = settings.recordingMode == RecordingMode.PYTHON_SERVER,
                onClick = {
                    onSettingsChange(settings.copy(recordingMode = RecordingMode.PYTHON_SERVER))
                },
                label = { Text(stringResource(R.string.settings_mode_server)) },
                leadingIcon = filterChipLeadingIcon(
                    settings.recordingMode == RecordingMode.PYTHON_SERVER,
                    Icons.Default.Cloud,
                )
            )
        }

        if (settings.recordingMode == RecordingMode.PYTHON_SERVER) {
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = stringResource(R.string.settings_mode_server_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = settings.serverBaseUrl,
                onValueChange = { onSettingsChange(settings.copy(serverBaseUrl = it)) },
                label = { Text(stringResource(R.string.settings_server_url_label)) },
                placeholder = { Text(stringResource(R.string.settings_server_url_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = settings.serverApiKey,
                onValueChange = { onSettingsChange(settings.copy(serverApiKey = it)) },
                label = { Text(stringResource(R.string.settings_server_api_key)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedButton(
                onClick = onTestServer,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isTestingServer
            ) {
                if (isTestingServer) {
                    LoadingSpinner(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    Text(stringResource(R.string.settings_server_testing))
                } else {
                    Icon(Icons.Default.WifiTethering, contentDescription = null)
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    Text(stringResource(R.string.settings_server_test))
                }
            }
        }

        Spacer(modifier = Modifier.height(Spacing.sm))
        SettingsSwitchRow(
            title = stringResource(R.string.settings_autoboot_title),
            subtitle = stringResource(R.string.settings_autoboot_subtitle),
            checked = settings.autoBootEnabled,
            onCheckedChange = { onSettingsChange(settings.copy(autoBootEnabled = it)) },
        )

        Spacer(modifier = Modifier.height(Spacing.sm))
        SettingsSwitchRow(
            title = stringResource(R.string.settings_motion_alert_title),
            subtitle = stringResource(R.string.settings_motion_alert_subtitle),
            checked = settings.motionAlertEnabled,
            onCheckedChange = { onSettingsChange(settings.copy(motionAlertEnabled = it)) },
        )
        if (settings.motionAlertEnabled) {
            // The recipient is now the other phone's push address, so a typo is caught here rather than at the
            // moment a motion event needs it: the server rejects a non-https target, and an alert that never
            // arrives is otherwise indistinguishable from a camera that saw nothing.
            val address = settings.motionAlertRecipient
            val addressUsable = address.isBlank() || isPushAddress(address)
            OutlinedTextField(
                value = address,
                onValueChange = { onSettingsChange(settings.copy(motionAlertRecipient = it)) },
                label = { Text(stringResource(R.string.settings_motion_alert_recipient_label)) },
                supportingText = {
                    Text(
                        text = stringResource(
                            if (addressUsable) R.string.settings_motion_alert_recipient_hint
                            else R.string.settings_motion_alert_recipient_invalid
                        ),
                        color = if (addressUsable) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                },
                isError = !addressUsable,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        Spacer(modifier = Modifier.height(Spacing.sm))
        SettingsSwitchRow(
            title = stringResource(R.string.settings_motion_alert_receive_title),
            subtitle = stringResource(R.string.settings_motion_alert_receive_subtitle),
            checked = pushState.receiveEnabled,
            onCheckedChange = onReceiveToggle,
        )
        if (pushState.receiveEnabled) {
            val endpoint = pushState.endpointUrl
            when {
                // Each of these says what the user can act on; none of them is a "working" state to copy.
                !pushState.distributorAvailable ->
                    MotionAlertStateLine(stringResource(R.string.settings_motion_alert_no_distributor))

                pushState.needsEncryption ->
                    MotionAlertStateLine(stringResource(R.string.settings_motion_alert_endpoint_encrypted))

                endpoint == null ->
                    MotionAlertStateLine(stringResource(R.string.settings_motion_alert_endpoint_missing))

                else -> {
                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.settings_motion_alert_endpoint_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                    )
                    val snackbarHost = LocalAppSnackbar.current
                    val scope = rememberCoroutineScope()
                    // Resolved as a composable resource, not through the hoisted Context: a click lambda that ran
                    // later would keep a string from the configuration that has since changed.
                    val copiedMessage = stringResource(R.string.settings_motion_alert_copied)
                    OutlinedButton(
                        onClick = {
                            copyAddress(context, endpoint)
                            scope.launch {
                                snackbarHost.showMessage(copiedMessage, short = true)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                        Spacer(modifier = Modifier.width(Spacing.sm))
                        Text(stringResource(R.string.settings_motion_alert_copy))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(Spacing.sm))
        SettingsSwitchRow(
            title = stringResource(R.string.settings_logs_toggle_title),
            subtitle = stringResource(R.string.settings_logs_toggle_subtitle),
            checked = settings.loggingToFileEnabled,
            onCheckedChange = { onSettingsChange(settings.copy(loggingToFileEnabled = it)) },
        )

        Spacer(modifier = Modifier.height(Spacing.sm))
        OutlinedButton(
            onClick = { shareLogs(context) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Share, contentDescription = null)
            Spacer(modifier = Modifier.width(Spacing.sm))
            Text(stringResource(R.string.settings_share_logs))
        }
    }
}

/**
 * A state line under the receiver switch: what is missing, or why the address this phone has cannot be used.
 * Quiet by design — the switch above it is the control, this only explains the situation it led to.
 */
@Composable
private fun MotionAlertStateLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Whether [value] is an address the server would accept as a recipient: absolute, https, with a host. */
private fun isPushAddress(value: String): Boolean =
    value.toHttpUrlOrNull()?.let { it.scheme == "https" && it.host.isNotBlank() } == true

/**
 * Puts the alert address on the clipboard. The whole URL is copied deliberately: it is the bearer token the
 * other phone has to be given, which is why it is never written to a log or the shared log file.
 */
private fun copyAddress(context: Context, endpoint: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.settings_motion_alert_endpoint_label), endpoint))
}

/**
 * Shares every non-empty log generation under `filesDir/logs` as readable text through the existing
 * FileProvider. Launched from the Composable rather than the ViewModel because the ViewModel deliberately
 * holds no [Context]; the share sheet starts from the Activity that [LocalContext] resolves to. When file
 * logging has never run there is nothing to send, so the action is a silent no-op.
 */
private fun shareLogs(context: Context) {
    val files = File(context.filesDir, FileLoggingPlanter.LOG_DIR_NAME)
        .listFiles { f -> f.isFile && f.length() > 0L }
        ?.sortedBy { it.name }
        ?: return
    val uris = ArrayList(
        files.map {
            FileProvider.getUriForFile(context, context.getString(R.string.fileprovider_authority), it)
        },
    )
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "text/plain"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(intent, context.getString(R.string.settings_share_logs)).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        },
    )
}
