package com.terminox.presentation.terminal.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.terminox.protocol.terminal.CellStyle
import com.terminox.protocol.terminal.TerminalColor
import com.terminox.protocol.terminal.TerminalLine
import com.terminox.protocol.terminal.TerminalState
import kotlinx.coroutines.delay

/**
 * Custom Canvas-based terminal renderer.
 * Optimized for performance with monospace font rendering.
 */
@Composable
fun TerminalCanvas(
    terminalState: TerminalState,
    modifier: Modifier = Modifier,
    fontSize: Float = 14f,
    onSizeChanged: ((columns: Int, rows: Int) -> Unit)? = null,
    onTap: (() -> Unit)? = null
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    // Calculate cell dimensions based on font size
    val cellDimensions = remember(fontSize) {
        calculateCellDimensions(textMeasurer, fontSize)
    }

    // Cursor blink state
    var cursorVisible by remember { mutableStateOf(true) }

    LaunchedEffect(terminalState.cursorVisible) {
        if (terminalState.cursorVisible) {
            while (true) {
                delay(530)
                cursorVisible = !cursorVisible
            }
        } else {
            cursorVisible = false
        }
    }

    // Track size and report column/row changes
    var lastReportedSize by remember { mutableStateOf(Pair(0, 0)) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E))
            .pointerInput(Unit) {
                detectTapGestures {
                    onTap?.invoke()
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasWidth = size.width
            val canvasHeight = size.height

            // Calculate visible columns and rows
            val visibleColumns = (canvasWidth / cellDimensions.width).toInt()
            val visibleRows = (canvasHeight / cellDimensions.height).toInt()

            // Report size changes
            if (visibleColumns > 0 && visibleRows > 0) {
                val newSize = Pair(visibleColumns, visibleRows)
                if (newSize != lastReportedSize) {
                    lastReportedSize = newSize
                    onSizeChanged?.invoke(visibleColumns, visibleRows)
                }
            }

            // Draw each line
            terminalState.lines.forEachIndexed { rowIndex, line ->
                if (rowIndex < visibleRows) {
                    drawTerminalLine(
                        line = line,
                        rowIndex = rowIndex,
                        cellDimensions = cellDimensions,
                        textMeasurer = textMeasurer,
                        fontSize = fontSize,
                        visibleColumns = visibleColumns
                    )
                }
            }

            // Draw cursor
            if (cursorVisible && terminalState.cursorVisible) {
                drawCursor(
                    row = terminalState.cursorRow,
                    column = terminalState.cursorColumn,
                    cellDimensions = cellDimensions
                )
            }
        }
    }
}

/**
 * Cell dimensions for the monospace grid.
 */
data class CellDimensions(
    val width: Float,
    val height: Float
)

private fun calculateCellDimensions(
    textMeasurer: TextMeasurer,
    fontSize: Float
): CellDimensions {
    val style = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = fontSize.sp
    )

    // Measure a reference character
    val result = textMeasurer.measure("M", style)

    return CellDimensions(
        width = result.size.width.toFloat(),
        height = result.size.height.toFloat() * 1.2f // Add line spacing
    )
}

private fun DrawScope.drawTerminalLine(
    line: TerminalLine,
    rowIndex: Int,
    cellDimensions: CellDimensions,
    textMeasurer: TextMeasurer,
    fontSize: Float,
    visibleColumns: Int
) {
    val y = rowIndex * cellDimensions.height

    line.cells.forEachIndexed { colIndex, cell ->
        if (colIndex >= visibleColumns) return@forEachIndexed

        val x = colIndex * cellDimensions.width
        val style = cell.style

        // Draw background if not default
        if (style.background != TerminalColor.Default) {
            val bgColor = if (style.attributes.inverse) {
                style.foreground.toComposeColor()
            } else {
                style.background.toComposeColor()
            }

            drawRect(
                color = bgColor,
                topLeft = Offset(x, y),
                size = Size(cellDimensions.width, cellDimensions.height)
            )
        }

        // Draw character
        if (cell.character != ' ') {
            val fgColor = if (style.attributes.inverse) {
                style.background.toComposeColor()
            } else {
                style.foreground.toComposeColor()
            }

            val textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = fontSize.sp,
                color = if (style.attributes.dim) fgColor.copy(alpha = 0.5f) else fgColor,
                fontWeight = if (style.attributes.bold) FontWeight.Bold else FontWeight.Normal,
                fontStyle = if (style.attributes.italic) FontStyle.Italic else FontStyle.Normal
            )

            drawText(
                textMeasurer = textMeasurer,
                text = cell.character.toString(),
                style = textStyle,
                topLeft = Offset(x, y)
            )

            // Draw underline
            if (style.attributes.underline) {
                drawLine(
                    color = fgColor,
                    start = Offset(x, y + cellDimensions.height - 2),
                    end = Offset(x + cellDimensions.width, y + cellDimensions.height - 2),
                    strokeWidth = 1f
                )
            }

            // Draw strikethrough
            if (style.attributes.strikethrough) {
                drawLine(
                    color = fgColor,
                    start = Offset(x, y + cellDimensions.height / 2),
                    end = Offset(x + cellDimensions.width, y + cellDimensions.height / 2),
                    strokeWidth = 1f
                )
            }
        }
    }
}

private fun DrawScope.drawCursor(
    row: Int,
    column: Int,
    cellDimensions: CellDimensions
) {
    val x = column * cellDimensions.width
    val y = row * cellDimensions.height

    // Block cursor
    drawRect(
        color = Color(0xFF00FF00).copy(alpha = 0.7f),
        topLeft = Offset(x, y),
        size = Size(cellDimensions.width, cellDimensions.height)
    )
}

/**
 * Preview-friendly terminal canvas for testing.
 */
@Composable
fun TerminalCanvasPreview() {
    val sampleState = TerminalState(
        lines = listOf(),
        cursorRow = 0,
        cursorColumn = 0,
        cursorVisible = true,
        columns = 80,
        rows = 24
    )

    TerminalCanvas(
        terminalState = sampleState,
        modifier = Modifier.fillMaxSize()
    )
}
