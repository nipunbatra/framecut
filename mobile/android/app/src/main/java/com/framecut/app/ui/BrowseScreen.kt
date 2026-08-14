package com.framecut.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.framecut.app.BrowseState
import com.framecut.app.drive.DriveItem
import com.framecut.app.drive.SortKey
import com.framecut.app.drive.isSharedRoot
import com.framecut.app.util.formatBytes
import com.framecut.app.util.formatDriveDate

@Composable
fun BrowseScreen(
    state: BrowseState,
    onOpenRoot: (Boolean) -> Unit,
    onNavigate: (Int) -> Unit,
    onOpenFolder: (DriveItem) -> Unit,
    onOpenVideo: (DriveItem) -> Unit,
    onFilterChange: (String) -> Unit,
    onSubmitSearch: (String) -> Unit,
    onSort: (SortKey) -> Unit,
    onRefresh: () -> Unit,
    onCreateFolder: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showNewFolder by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    Column(modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            SegmentedButton(
                selected = !state.path.first().isSharedRoot(),
                onClick = { onOpenRoot(false) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text("My Drive") }
            SegmentedButton(
                selected = state.path.first().isSharedRoot(),
                onClick = { onOpenRoot(true) },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) { Text("Shared with me") }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Breadcrumbs(
                path = if (state.searchMode) {
                    listOf(state.path.first(), com.framecut.app.drive.Crumb("search", "“${state.searchQuery}”"))
                } else {
                    state.path
                },
                onNavigate = onNavigate,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
            }
        }

        OutlinedTextField(
            value = state.filterText,
            onValueChange = onFilterChange,
            singleLine = true,
            label = { Text("Filter this folder — press Search for all of Drive") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                focusManager.clearFocus()
                onSubmitSearch(state.filterText)
            }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SortKey.entries.forEach { key ->
                val active = state.sortKey == key
                FilterChip(
                    selected = active,
                    onClick = { onSort(key) },
                    label = {
                        Text(key.label + if (active) (if (state.sortAscending) " ↑" else " ↓") else "")
                    },
                )
            }
            Box(Modifier.weight(1f))
            if (!state.current.isSharedRoot() && !state.searchMode) {
                IconButton(onClick = { showNewFolder = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "New folder")
                }
            }
        }

        HorizontalDivider()

        // Non-video files are listed by Drive but not actionable here.
        val hiddenCount = state.visible.count { !it.isFolder && !it.isVideo }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.error != null -> ErrorState(state.error, onRefresh)

                state.visible.isEmpty() && state.loading ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }

                else -> ItemList(
                    state = state,
                    hiddenCount = hiddenCount,
                    onOpenFolder = onOpenFolder,
                    onOpenVideo = onOpenVideo,
                )
            }
        }
    }

    if (showNewFolder) {
        NewFolderDialog(onDismiss = { showNewFolder = false }, onCreate = onCreateFolder)
    }
}

@Composable
private fun ItemList(
    state: BrowseState,
    hiddenCount: Int,
    onOpenFolder: (DriveItem) -> Unit,
    onOpenVideo: (DriveItem) -> Unit,
) {
    // Only folders and videos are actionable; everything else is counted below.
    val rows = state.visible.filter { it.isFolder || it.isVideo }
    if (rows.isEmpty() && !state.loading) {
        EmptyState(
            when {
                state.searchMode -> "Nothing in Drive matches this search."
                state.filterText.isNotBlank() -> "No matches in this folder."
                else -> "No folders or videos here."
            },
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(rows, key = { it.id }) { item ->
            ListItem(
                headlineContent = {
                    Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = {
                    val meta = if (item.isFolder) {
                        formatDriveDate(item.modifiedTime)
                    } else {
                        listOf(formatBytes(item.size), formatDriveDate(item.modifiedTime))
                            .filter { it.isNotEmpty() }
                            .joinToString(" · ")
                    }
                    if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall)
                },
                leadingContent = { RowIcon(item.isFolder) },
                modifier = Modifier.clickable {
                    if (item.isFolder) onOpenFolder(item) else onOpenVideo(item)
                },
            )
            HorizontalDivider()
        }
        if (state.loading) {
            item {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
        if (hiddenCount > 0) {
            item {
                Text(
                    "$hiddenCount non-video file${if (hiddenCount > 1) "s" else ""} hidden",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}
