package com.framecut.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

private const val NONE = 0
private const val START_HANDLE = 1
private const val END_HANDLE = 2

/**
 * Scrubbable timeline with draggable in/out handles. Tapping or dragging away
 * from a handle moves the playhead.
 */
@Composable
fun TrimTimeline(
    durationMs: Long,
    startMs: Long,
    endMs: Long,
    playheadMs: Long,
    onStartChange: (Long) -> Unit,
    onEndChange: (Long) -> Unit,
    onScrub: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val duration = durationMs.coerceAtLeast(1)
    // pointerInput keeps its lambda across recompositions, so the live values
    // have to be read through an updated state rather than captured.
    val latest by rememberUpdatedState(Triple(startMs, endMs, duration))

    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val handleColor = MaterialTheme.colorScheme.primary
    val playheadColor = MaterialTheme.colorScheme.tertiary

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .pointerInput(Unit) {
                val slop = 24.dp.toPx()
                var dragging = NONE
                detectDragGestures(
                    onDragStart = { position ->
                        val width = size.width.toFloat().coerceAtLeast(1f)
                        val (start, end, total) = latest
                        val startX = start.toFloat() / total * width
                        val endX = end.toFloat() / total * width
                        val dStart = abs(position.x - startX)
                        val dEnd = abs(position.x - endX)
                        dragging = when {
                            dStart <= slop && dStart <= dEnd -> START_HANDLE
                            dEnd <= slop -> END_HANDLE
                            else -> NONE
                        }
                        if (dragging == NONE) onScrub(msAt(position.x, width, total))
                    },
                    onDragEnd = { dragging = NONE },
                    onDragCancel = { dragging = NONE },
                    onDrag = { change, _ ->
                        change.consume()
                        val width = size.width.toFloat().coerceAtLeast(1f)
                        val ms = msAt(change.position.x, width, latest.third)
                        when (dragging) {
                            START_HANDLE -> onStartChange(ms)
                            END_HANDLE -> onEndChange(ms)
                            else -> onScrub(ms)
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures { position ->
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    onScrub(msAt(position.x, width, latest.third))
                }
            },
    ) {
        val width = size.width
        val trackTop = size.height * 0.25f
        val trackHeight = size.height * 0.5f
        val startX = (startMs.toFloat() / duration * width).coerceIn(0f, width)
        val endX = (endMs.toFloat() / duration * width).coerceIn(0f, width)
        val playX = (playheadMs.toFloat() / duration * width).coerceIn(0f, width)

        drawRoundRect(
            color = trackColor,
            topLeft = Offset(0f, trackTop),
            size = Size(width, trackHeight),
            cornerRadius = CornerRadius(6f, 6f),
        )
        drawRect(
            color = selectionColor,
            topLeft = Offset(startX, trackTop),
            size = Size((endX - startX).coerceAtLeast(0f), trackHeight),
        )
        // Handles: full-height grips so they stay easy to grab on a phone.
        drawHandle(startX, handleColor, size.height)
        drawHandle(endX, handleColor, size.height)
        drawLine(
            color = playheadColor,
            start = Offset(playX, 0f),
            end = Offset(playX, size.height),
            strokeWidth = 3f,
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHandle(
    x: Float,
    color: Color,
    height: Float,
) {
    val halfWidth = 5f
    drawRoundRect(
        color = color,
        topLeft = Offset(x - halfWidth, 0f),
        size = Size(halfWidth * 2, height),
        cornerRadius = CornerRadius(4f, 4f),
    )
}

private fun msAt(x: Float, width: Float, durationMs: Long): Long =
    ((x / width).coerceIn(0f, 1f) * durationMs).toLong()
