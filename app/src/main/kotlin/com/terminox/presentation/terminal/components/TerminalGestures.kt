package com.terminox.presentation.terminal.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Represents gesture events from the terminal canvas.
 */
sealed class TerminalGesture {
    data class Tap(val position: Offset) : TerminalGesture()
    data class DoubleTap(val position: Offset) : TerminalGesture()
    data class LongPress(val position: Offset) : TerminalGesture()
    data class TwoFingerScroll(val deltaY: Float) : TerminalGesture()
    data class PinchZoom(val scaleFactor: Float) : TerminalGesture()
    data class SwipeFromEdge(val edge: Edge, val progress: Float) : TerminalGesture()
    data class SelectionDrag(val start: Offset, val end: Offset) : TerminalGesture()
}

enum class Edge {
    LEFT,
    RIGHT,
    TOP,
    BOTTOM
}

/**
 * State holder for terminal gesture handling.
 */
data class TerminalGestureState(
    val fontSize: Float = 14f,
    val scrollOffset: Float = 0f,
    val isSelecting: Boolean = false,
    val selectionStart: Offset? = null,
    val selectionEnd: Offset? = null
)

/**
 * Configuration for terminal gestures.
 */
data class GestureConfig(
    val minFontSize: Float = 8f,
    val maxFontSize: Float = 32f,
    val fontSizeStep: Float = 1f,
    val edgeSwipeThreshold: Float = 50f,
    val scrollSensitivity: Float = 1.5f,
    val pinchZoomSensitivity: Float = 0.05f
)

/**
 * Modifier that adds comprehensive terminal gesture handling.
 *
 * Supports:
 * - Single tap: Focus keyboard
 * - Double tap: Select word at position
 * - Long press: Start text selection mode
 * - Two-finger scroll: Scroll terminal history
 * - Pinch zoom: Adjust font size
 * - Swipe from left edge: Open session drawer
 */
fun Modifier.terminalGestures(
    config: GestureConfig = GestureConfig(),
    screenWidth: Float = 0f,
    onGesture: (TerminalGesture) -> Unit
): Modifier = composed {
    var lastPointerCount by remember { mutableStateOf(0) }
    var accumulatedScrollY by remember { mutableFloatStateOf(0f) }
    var accumulatedZoom by remember { mutableFloatStateOf(1f) }
    var isMultiTouch by remember { mutableStateOf(false) }
    var selectionStartPos by remember { mutableStateOf<Offset?>(null) }

    this
        .pointerInput(Unit) {
            detectTapGestures(
                onTap = { offset ->
                    if (!isMultiTouch) {
                        onGesture(TerminalGesture.Tap(offset))
                    }
                },
                onDoubleTap = { offset ->
                    onGesture(TerminalGesture.DoubleTap(offset))
                },
                onLongPress = { offset ->
                    selectionStartPos = offset
                    onGesture(TerminalGesture.LongPress(offset))
                }
            )
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                val firstDown = awaitFirstDown(requireUnconsumed = false)
                isMultiTouch = false
                accumulatedZoom = 1f
                accumulatedScrollY = 0f

                // Check for edge swipe
                val startX = firstDown.position.x
                val isLeftEdge = startX < config.edgeSwipeThreshold
                val isRightEdge = screenWidth > 0 && startX > screenWidth - config.edgeSwipeThreshold

                do {
                    val event = awaitPointerEvent()
                    val pointerCount = event.changes.size

                    if (pointerCount >= 2) {
                        isMultiTouch = true

                        // Calculate zoom
                        val zoomChange = event.calculateZoom()
                        if (zoomChange != 1f) {
                            accumulatedZoom *= zoomChange
                            val scaleFactor = (accumulatedZoom - 1f) * config.pinchZoomSensitivity
                            onGesture(TerminalGesture.PinchZoom(scaleFactor))
                        }

                        // Calculate two-finger pan for scrolling
                        val pan = event.calculatePan()
                        if (pan.y != 0f) {
                            accumulatedScrollY += pan.y * config.scrollSensitivity
                            onGesture(TerminalGesture.TwoFingerScroll(pan.y * config.scrollSensitivity))
                        }

                        event.changes.forEach { it.consume() }
                    } else if (pointerCount == 1) {
                        val change = event.changes.first()

                        // Handle edge swipe
                        if (isLeftEdge && change.positionChanged()) {
                            val deltaX = change.position.x - startX
                            if (deltaX > 0) {
                                val progress = (deltaX / (screenWidth * 0.3f)).coerceIn(0f, 1f)
                                onGesture(TerminalGesture.SwipeFromEdge(Edge.LEFT, progress))
                            }
                        } else if (isRightEdge && change.positionChanged()) {
                            val deltaX = startX - change.position.x
                            if (deltaX > 0) {
                                val progress = (deltaX / (screenWidth * 0.3f)).coerceIn(0f, 1f)
                                onGesture(TerminalGesture.SwipeFromEdge(Edge.RIGHT, progress))
                            }
                        }

                        // Handle selection drag
                        selectionStartPos?.let { start ->
                            if (change.positionChanged()) {
                                onGesture(TerminalGesture.SelectionDrag(start, change.position))
                            }
                        }
                    }

                    lastPointerCount = pointerCount
                } while (event.changes.any { it.pressed })

                // Reset selection on release
                selectionStartPos = null
            }
        }
}

/**
 * Helper to convert screen position to terminal cell coordinates.
 */
fun screenPositionToCell(
    position: Offset,
    cellWidth: Float,
    cellHeight: Float
): Pair<Int, Int> {
    val column = (position.x / cellWidth).toInt()
    val row = (position.y / cellHeight).toInt()
    return column to row
}

/**
 * Text selection state for the terminal.
 */
data class TextSelection(
    val startRow: Int,
    val startColumn: Int,
    val endRow: Int,
    val endColumn: Int
) {
    val isValid: Boolean
        get() = startRow >= 0 && startColumn >= 0 && endRow >= 0 && endColumn >= 0

    fun isEmpty(): Boolean {
        return startRow == endRow && startColumn == endColumn
    }

    fun normalize(): TextSelection {
        // Ensure start comes before end
        return if (startRow > endRow || (startRow == endRow && startColumn > endColumn)) {
            TextSelection(endRow, endColumn, startRow, startColumn)
        } else {
            this
        }
    }
}
