package com.framecut.app.ui

import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.framecut.app.TrimState
import com.framecut.app.util.formatBytes
import com.framecut.app.util.formatClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun TrimScreen(
    state: TrimState,
    busy: Boolean,
    onSetSelection: (Long, Long) -> Unit,
    onSetStartAt: (Long) -> Unit,
    onSetEndAt: (Long) -> Unit,
    onNameChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onPickDestination: () -> Unit,
    onSaveToDrive: () -> Unit,
    onSaveToDevice: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var player by remember { mutableStateOf<VideoView?>(null) }
    var playheadMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var previewingSelection by remember { mutableStateOf(false) }

    // One poll loop drives the playhead; MediaPlayer has no position callback.
    LaunchedEffect(player) {
        while (isActive) {
            val view = player
            if (view != null) {
                playheadMs = view.currentPosition.toLong()
                isPlaying = view.isPlaying
                if (previewingSelection && playheadMs >= state.endMs) {
                    view.pause()
                    previewingSelection = false
                    isPlaying = false
                }
            }
            delay(80)
        }
    }

    DisposableEffect(Unit) {
        onDispose { player?.stopPlayback() }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { context ->
                    VideoView(context).apply {
                        setVideoPath(state.localPath)
                        setOnPreparedListener { seekTo(state.startMs.toInt()) }
                        player = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = {
                val view = player ?: return@OutlinedButton
                if (view.isPlaying) {
                    view.pause()
                    previewingSelection = false
                } else {
                    view.start()
                }
            }) { Text(if (isPlaying) "Pause" else "Play") }

            OutlinedButton(onClick = {
                val view = player ?: return@OutlinedButton
                view.seekTo(state.startMs.toInt())
                previewingSelection = true
                view.start()
            }) { Text("Preview cut") }

            Box(Modifier.weight(1f))
            Text(formatClock(playheadMs), style = MaterialTheme.typography.titleMedium)
        }

        TrimTimeline(
            durationMs = state.durationMs,
            startMs = state.startMs,
            endMs = state.endMs,
            playheadMs = playheadMs,
            onStartChange = { onSetSelection(it, state.endMs) },
            onEndChange = { onSetSelection(state.startMs, it) },
            onScrub = {
                previewingSelection = false
                player?.seekTo(it.toInt())
                playheadMs = it
            },
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { onSetStartAt(playheadMs) },
                modifier = Modifier.weight(1f),
            ) { Text("Set start") }
            OutlinedButton(
                onClick = { onSetEndAt(playheadMs) },
                modifier = Modifier.weight(1f),
            ) { Text("Set end") }
        }

        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Readout("Start", formatClock(state.startMs))
            Readout("End", formatClock(state.endMs))
            Readout("Kept", formatClock(state.keptMs))
        }

        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(
                "Lossless copy — no re-encoding. The cut snaps back to the nearest keyframe " +
                    "before your start point, so the result may begin up to a few seconds early.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
        }

        Text(
            "${state.sourceName} · ${formatBytes(state.sourceSize)} · ${formatClock(state.durationMs)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )

        HorizontalDivider()

        OutlinedTextField(
            value = state.outputName,
            onValueChange = onNameChange,
            singleLine = true,
            label = { Text("File name") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        OutlinedTextField(
            value = state.description,
            onValueChange = onDescriptionChange,
            label = { Text("Description (optional)") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Save to: ${state.destination?.name ?: "My Drive"}", Modifier.weight(1f))
            TextButton(onClick = onPickDestination) { Text("Change") }
        }

        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { player?.pause(); onSaveToDrive() },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) { Text("Trim & upload") }
            OutlinedButton(
                onClick = { player?.pause(); onSaveToDevice() },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) { Text("Save to device") }
        }

        TextButton(
            onClick = onBack,
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp),
        ) { Text("Choose a different video") }
    }
}

@Composable
private fun Readout(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}
