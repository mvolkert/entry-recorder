package io.github.mvolkert.entryrecorder.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity

/**
 * Create or edit an intercom. The form values live in a [DeviceFormState] seeded from
 * [initialDevice]; the sections below render and mutate that state, and [DeviceEntity] is only
 * assembled when the user saves.
 */
@Composable
fun DeviceEditDialog(
    initialDevice: DeviceEntity? = null,
    onDismiss: () -> Unit,
    onSave: (DeviceEntity) -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val form = remember {
        DeviceFormState(initialDevice, resources.getString(R.string.device_default_name))
    }

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
                    DeviceFormNetworkSection(form)

                    FormDivider()

                    DeviceFormStreamSection(form)

                    FormDivider()

                    DeviceFormSipSection(form)

                    FormDivider()

                    DeviceFormTriggersSection(form)

                    FormDivider()

                    DeviceFormDurationsSection(form)

                    DeviceFormTestSection(form)
                }

                Spacer(modifier = Modifier.height(12.dp))

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
                            if (!form.isValid) {
                                Toast.makeText(context, R.string.device_toast_name_ip_required, Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            onSave(form.buildDevice())
                        }
                    ) {
                        Text(stringResource(R.string.device_save))
                    }
                }
            }
        }
    }
}
