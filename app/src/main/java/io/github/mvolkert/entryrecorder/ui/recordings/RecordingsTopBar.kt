package io.github.mvolkert.entryrecorder.ui.recordings

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.util.RecordingExportKind

/**
 * Recordings title band. A real [TopAppBar] so its height, titleLarge typography and status-bar
 * inset match the Live and Settings tabs; multi-select actions sit in its action row.
 *
 * The bar paints itself transparent: the band that holds it (see [RecordingsScreen]) animates `surface` →
 * `surfaceContainer` off the same [scrollBehavior] state, and a second paint under it would show a seam
 * where the bar ends and the search strip begins. Content scrolling under it is what drives the step.
 *
 * In selection mode the title is the batch: [selectedCount] rows, plus a note when [hiddenSelectedCount]
 * of them are checked but filtered away, so the number never claims fewer rows than the operation covers.
 * The two batch actions only appear once a row is picked - a greyed export and a greyed delete say
 * "nothing to do here" louder than their absence, and entering this mode means "start picking rows".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecordingsTopBar(
    selectionMode: Boolean,
    selectedCount: Int,
    hiddenSelectedCount: Int = 0,
    onSelectAll: () -> Unit,
    onExportSelected: (RecordingExportKind) -> Unit,
    onBulkDelete: () -> Unit,
    onExitSelection: () -> Unit,
    onEnterSelection: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    modifier: Modifier = Modifier,
) {
    var showExportMenu by remember { mutableStateOf(false) }

    TopAppBar(
        modifier = modifier,
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
        ),
        title = {
            Text(
                text = when {
                    !selectionMode -> stringResource(R.string.recordings_title)
                    hiddenSelectedCount == 0 -> stringResource(R.string.recordings_selected_count, selectedCount)
                    else -> stringResource(R.string.recordings_selected_count, selectedCount) +
                        // One line, so the two facts are joined by the app's usual middot separator.
                        " · " + stringResource(R.string.recordings_selected_hidden, hiddenSelectedCount)
                },
                modifier = Modifier.semantics { heading() }
            )
        },
        actions = {
            if (selectionMode) {
                IconButton(onClick = onSelectAll) {
                    Icon(Icons.Default.SelectAll, contentDescription = stringResource(R.string.recordings_cd_select_all))
                }
                if (selectedCount > 0) {
                    Box {
                        IconButton(onClick = { showExportMenu = true }) {
                            Icon(Icons.Default.Upload, contentDescription = stringResource(R.string.recordings_cd_export_selected))
                        }
                        // [DropdownMenuPopup] only sizes and positions the surface; the group paints it, and
                        // an item's shape comes from its index in the group — hence the ordered list below.
                        DropdownMenuPopup(
                            expanded = showExportMenu,
                            onDismissRequest = { showExportMenu = false }
                        ) {
                            DropdownMenuGroup(shapes = MenuDefaults.groupShapes()) {
                                BatchExportOptions.forEachIndexed { index, option ->
                                    DropdownMenuItem(
                                        onClick = {
                                            showExportMenu = false
                                            onExportSelected(option.kind)
                                        },
                                        text = { Text(stringResource(option.labelRes)) },
                                        leadingIcon = { Icon(option.icon, contentDescription = null) },
                                        shape = MenuDefaults.itemShape(
                                            index = index,
                                            count = BatchExportOptions.size
                                        ).shape,
                                    )
                                }
                            }
                        }
                    }
                    IconButton(onClick = onBulkDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.recordings_cd_delete_selected),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
                IconButton(onClick = onExitSelection) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.recordings_cd_exit_selection))
                }
            } else {
                IconButton(onClick = onEnterSelection) {
                    Icon(Icons.Default.Checklist, contentDescription = stringResource(R.string.recordings_cd_select_multiple))
                }
            }
        }
    )
}

/** Storage summary under the title band; the parent supplies its horizontal padding. */
@Composable
internal fun RecordingsStorageLine(
    totalStorageBytes: Long,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Text(
        text = stringResource(
            R.string.recordings_total_storage,
            Formatter.formatFileSize(context, totalStorageBytes)
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

/** One entry of the batch-export menu. */
private class BatchExportOption(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val kind: RecordingExportKind,
)

/**
 * The four export destinations in menu order. The expressive menu gives only the first and the last
 * item of a group their rounded shape, so the list has to be data the menu can index.
 */
private val BatchExportOptions = listOf(
    BatchExportOption(R.string.recordings_share_batch, Icons.Default.Share, RecordingExportKind.SHARE),
    BatchExportOption(
        R.string.recordings_save_gallery_menu,
        Icons.Default.Download,
        RecordingExportKind.GALLERY
    ),
    BatchExportOption(
        R.string.recordings_export_folder_batch,
        Icons.Default.SaveAlt,
        RecordingExportKind.FOLDER
    ),
    // The unconverted copy is the one entry that does not produce a playable file, so
    // it gets the open-folder icon instead of a fourth save-shaped one.
    BatchExportOption(
        R.string.recordings_export_raw_folder_batch,
        Icons.Default.FolderOpen,
        RecordingExportKind.RAW_FOLDER
    ),
)
