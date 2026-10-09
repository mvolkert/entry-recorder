package io.github.mvolkert.entryrecorder.ui.settings

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mvolkert.entryrecorder.EntryRecorderApp
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.ui.adaptive.appContentMaxWidth

/** Sentinel device id for "creating a new device" (no existing row matches it). */
const val NEW_DEVICE_ID = -1L

/**
 * Fullscreen create/edit for an intercom, reached from Settings via the NavHost. Replaces the old
 * `DeviceEditDialog`: the form has too many sections to live in a 0.9-height modal, and a real screen
 * gets a proper top bar (back + Save) and the full scrollable viewport without the pager or bottom
 * navigation bar interfering.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun DeviceEditScreen(
    viewModel: SettingsViewModel,
    deviceId: Long,
    onDone: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Seeded once when the screen opens for this id; the devices list is already loaded from Room
    // because Settings was showing it. Reads the current value inside the keyed `remember`, so later
    // unrelated device saves do not reset an in-progress edit.
    val form = remember(deviceId) {
        val initial = if (deviceId == NEW_DEVICE_ID) null
        else state.devices.firstOrNull { it.id == deviceId }
        DeviceFormState(initial, resources.getString(R.string.device_default_name))
    }

    // The app-scoped SIP singleton, reached the same way the call screen does. Touching it here only
    // resolves the lazy holder; no Linphone core is created until monitoring or a probe starts one.
    val sipCallManager = (context.applicationContext as EntryRecorderApp).sipCallManager

    val save = {
        if (!form.isValid) {
            Toast.makeText(context, R.string.device_toast_name_ip_required, Toast.LENGTH_SHORT).show()
        } else {
            viewModel.saveDevice(form.buildDevice())
            onDone()
        }
    }

    Scaffold(
        modifier = with(sharedTransitionScope) {
            Modifier.sharedBounds(
                rememberSharedContentState(key = "device-$deviceId"),
                animatedVisibilityScope,
            )
        },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (deviceId == NEW_DEVICE_ID) R.string.device_add_title
                            else R.string.device_edit_title
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.device_edit_back)
                        )
                    }
                },
                actions = {
                    TextButton(onClick = save) {
                        Text(stringResource(R.string.device_save))
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Caps and centers the form on wide windows so fields never stretch to tablet width.
            Column(
                modifier = Modifier
                    .appContentMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SettingsCard { DeviceFormNetworkSection(form) }

                SettingsCard { DeviceFormStreamSection(form) }

                SettingsCard { DeviceFormSipSection(form) }

                SettingsCard { DeviceFormTriggersSection(form) }

                SettingsCard { DeviceFormAlertsSection(form) }

                SettingsCard { DeviceFormDurationsSection(form) }

                SettingsCard { DeviceFormTestSection(form, sipCallManager) }

                // Bottom breathing room so the last field clears the navigation gesture area.
                Text(
                    text = stringResource(R.string.device_edit_footer_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}
