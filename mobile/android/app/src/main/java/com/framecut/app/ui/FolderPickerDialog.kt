package com.framecut.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.framecut.app.FolderPickerState
import com.framecut.app.drive.DriveItem
import com.framecut.app.drive.isSharedRoot

/**
 * Destination chooser. Folders only. The virtual "Shared with me" root is
 * browsable but cannot itself be a destination, so Choose is disabled there.
 */
@Composable
fun FolderPickerDialog(
    state: FolderPickerState,
    onOpenRoot: (Boolean) -> Unit,
    onEnter: (DriveItem) -> Unit,
    onNavigate: (Int) -> Unit,
    onCreateFolder: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var showNewFolder by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a destination") },
        text = {
            Column {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !state.path.first().isSharedRoot(),
                        onClick = { onOpenRoot(false) },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text("My Drive") }
                    SegmentedButton(
                        selected = state.path.first().isSharedRoot(),
                        onClick = { onOpenRoot(true) },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text("Shared") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Breadcrumbs(state.path, onNavigate, Modifier.weight(1f))
                    if (!state.current.isSharedRoot()) {
                        IconButton(onClick = { showNewFolder = true }) {
                            Icon(Icons.Filled.Add, contentDescription = "New folder")
                        }
                    }
                }
                HorizontalDivider()
                Box(Modifier.fillMaxWidth().height(280.dp)) {
                    when {
                        state.loading -> Box(
                            Modifier.fillMaxWidth().height(280.dp),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator() }

                        state.error != null -> ErrorState(
                            message = state.error,
                            onRetry = { onNavigate(state.path.lastIndex) },
                        )

                        state.folders.isEmpty() -> EmptyState("No subfolders here.")

                        else -> LazyColumn {
                            items(state.folders, key = { it.id }) { folder ->
                                ListItem(
                                    headlineContent = {
                                        Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    },
                                    leadingContent = { RowIcon(isFolder = true) },
                                    modifier = Modifier.clickable { onEnter(folder) },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = state.canChoose) {
                Text(if (state.canChoose) "Save here" else "Pick a folder")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        modifier = Modifier.padding(8.dp),
    )

    if (showNewFolder) {
        NewFolderDialog(onDismiss = { showNewFolder = false }, onCreate = onCreateFolder)
    }
}
