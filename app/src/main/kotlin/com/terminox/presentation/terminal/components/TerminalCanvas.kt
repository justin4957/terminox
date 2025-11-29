package com.terminox.presentation.terminal.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.terminox.domain.model.CursorStyle
import com.terminox.domain.model.TerminalTheme
import com.terminox.domain.model.TerminalThemes
import com.terminox.protocol.terminal.TerminalColor
import com.terminox.protocol.terminal.TerminalLine
import com.terminox.protocol.terminal.TerminalState
import kotlinx.coroutines.delay

/**
 * Custom Canvas-based terminal renderer.
 * Optimized for performance with monospace font rendering.
 * Supports gestures for scrolling, zooming, and text selection.
 */
@Composable
fun TerminalCanvas(
    terminalState: TerminalState,
    modifier: Modifier = Modifier,
    fontSize: Float = 14f,
    theme: TerminalTheme = TerminalThemes.TERMINOX_DARK,
    cursorStyle: CursorStyle = CursorStyle.BLOCK,
    cursorBlink: Boolean = true,
    selection: TextSelection? = null,
    scrollOffset: Int = 0,
    onSizeChanged: ((columns: Int, rows: Int) -> Unit)? = null,
    onTap: (() -> Unit)? = null,
    onGesture: ((TerminalGesture) -> Unit)? = null
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    // Calculate cell dimensions based on font size
    val cellDimensions = remember(fontSize) {
        calculateCellDimensions(textMeasurer, fontSize)
    }

    // Cursor blink state
    var cursorVisible by remember { mutableStateOf(true) }

    LaunchedEffect(terminalState.cursorVisible, cursorBlink) {
        if (terminalState.cursorVisible && cursorBlink) {
            while (true) {
                delay(530)
                cursorVisible = !cursorVisible
            }
        } else {
            cursorVisible = terminalState.cursorVisible
        }
    }

    // Track size and report column/row changes
    var lastReportedSize by remember { mutableStateOf(Pair(0, 0)) }
    var screenWidth by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(theme.background)
            .terminalGestures(
                screenWidth = screenWidth,
                onGesture = { gesture ->
                    when (gesture) {
                        is TerminalGesture.Tap -> onTap?.invoke()
                        else -> onGesture?.invoke(gesture)
                    }
                }
            )
    ) {
        screenWidth = constraints.maxWidth.toFloat()

        // Selection overlay
        if (selection != null && !selection.isEmpty()) {
            SelectionOverlay(
                selection = selection,
                cellWidth = cellDimensions.width,
                cellHeight = cellDimensions.height,
                modifier = Modifier.fillMaxSize()
            )
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasWidth = size.width
            val canvasHeight = size.height

            // Calculate how many columns/rows fit in the canvas
            val fittingColumns = (canvasWidth / cellDimensions.width).toInt()
            val fittingRows = (canvasHeight / cellDimensions.height).toInt()

            // Report size changes to update SSH PTY and emulator
            if (fittingColumns > 0 && fittingRows > 0) {
                val newSize = Pair(fittingColumns, fittingRows)
                if (newSize != lastReportedSize) {
                    lastReportedSize = newSize
                    onSizeChanged?.invoke(fittingColumns, fittingRows)
                }
            }

            // Use the terminal emulator's column count for rendering (source of truth)
            // This ensures rendering matches what the SSH server thinks the width is
            val renderColumns = terminalState.columns
            val renderRows = terminalState.rows

            // Draw each line using emulator's dimensions
            terminalState.lines.forEachIndexed { rowIndex, line ->
                if (rowIndex < renderRows) {
                    drawTerminalLine(
                        line = line,
                        rowIndex = rowIndex,
                        cellDimensions = cellDimensions,
                        textMeasurer = textMeasurer,
                        fontSize = fontSize,
                        visibleColumns = renderColumns,
                        theme = theme
                    )
                }
            }

            // Draw cursor
            if (cursorVisible && terminalState.cursorVisible) {
                drawCursor(
                    row = terminalState.cursorRow,
                    column = terminalState.cursorColumn,
                    cellDimensions = cellDimensions,
                    cursorStyle = cursorStyle,
                    cursorColor = theme.cursor
                )
            }
        }
    }
}

/**
 * Exports cell dimensions for external use.
 */
@Composable
fun rememberCellDimensions(fontSize: Float): CellDimensions {
    val textMeasurer = rememberTextMeasurer()
    return remember(fontSize) {
        calculateCellDimensions(textMeasurer, fontSize)
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
    visibleColumns: Int,
    theme: TerminalTheme
) {
    val y = rowIndex * cellDimensions.height

    line.cells.forEachIndexed { colIndex, cell ->
        if (colIndex >= visibleColumns) return@forEachIndexed

        val x = colIndex * cellDimensions.width
        val style = cell.style

        // Draw background if not default
        if (style.background != TerminalColor.Default) {
            val bgColor = if (style.attributes.inverse) {
                style.foreground.toComposeColor(theme)
            } else {
                style.background.toComposeColor(theme)
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
                if (style.background == TerminalColor.Default) theme.background else style.background.toComposeColor(theme)
            } else {
                if (style.foreground == TerminalColor.Default) theme.foreground else style.foreground.toComposeColor(theme)
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
    cellDimensions: CellDimensions,
    cursorStyle: CursorStyle,
    cursorColor: Color
) {
    val x = column * cellDimensions.width
    val y = row * cellDimensions.height

    when (cursorStyle) {
        CursorStyle.BLOCK -> {
            drawRect(
                color = cursorColor.copy(alpha = 0.7f),
                topLeft = Offset(x, y),
                size = Size(cellDimensions.width, cellDimensions.height)
            )
        }
        CursorStyle.UNDERLINE -> {
            val underlineHeight = 2f
            drawRect(
                color = cursorColor,
                topLeft = Offset(x, y + cellDimensions.height - underlineHeight),
                size = Size(cellDimensions.width, underlineHeight)
            )
        }
        CursorStyle.BAR -> {
            val barWidth = 2f
            drawRect(
                color = cursorColor,
                topLeft = Offset(x, y),
                size = Size(barWidth, cellDimensions.height)
            )
        }
    }
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
