package com.terminox.protocol.terminal

/**
 * Represents a single cell in the terminal grid.
 */
data class TerminalCell(
    val character: Char = ' ',
    val style: CellStyle = CellStyle.DEFAULT,
    val width: Int = 1 // 1 for normal chars, 2 for wide chars (CJK)
) {
    companion object {
        val EMPTY = TerminalCell()
    }
}

/**
 * Represents a single line in the terminal.
 */
data class TerminalLine(
    val cells: MutableList<TerminalCell>,
    var wrapped: Boolean = false
) {
    constructor(columns: Int) : this(
        cells = MutableList(columns) { TerminalCell.EMPTY },
        wrapped = false
    )

    fun getCell(column: Int): TerminalCell {
        return if (column in cells.indices) cells[column] else TerminalCell.EMPTY
    }

    fun setCell(column: Int, cell: TerminalCell) {
        if (column in cells.indices) {
            cells[column] = cell
        }
    }

    fun clear(style: CellStyle = CellStyle.DEFAULT) {
        for (i in cells.indices) {
            cells[i] = TerminalCell(' ', style)
        }
    }

    fun clearRange(start: Int, end: Int, style: CellStyle = CellStyle.DEFAULT) {
        for (i in start until minOf(end, cells.size)) {
            cells[i] = TerminalCell(' ', style)
        }
    }
}

/**
 * Terminal buffer managing the screen content and scrollback.
 */
class TerminalBuffer(
    var columns: Int = 80,
    var rows: Int = 24,
    private val maxScrollback: Int = 10000
) {
    // Active screen lines
    private val screenLines = mutableListOf<TerminalLine>()

    // Scrollback buffer (lines that scrolled off the top)
    private val scrollbackLines = mutableListOf<TerminalLine>()

    // Cursor position (0-indexed)
    var cursorRow: Int = 0
        private set
    var cursorColumn: Int = 0
        private set

    // Saved cursor position
    private var savedCursorRow: Int = 0
    private var savedCursorColumn: Int = 0
    private var savedStyle: CellStyle = CellStyle.DEFAULT

    // Current text style
    var currentStyle: CellStyle = CellStyle.DEFAULT

    // Scroll region (for scroll operations)
    var scrollTop: Int = 0
        private set
    var scrollBottom: Int = rows - 1
        private set

    // Terminal modes
    var originMode: Boolean = false // DECOM
    var autoWrapMode: Boolean = true // DECAWM
    var insertMode: Boolean = false // IRM
    var cursorVisible: Boolean = true

    init {
        initializeScreen()
    }

    private fun initializeScreen() {
        screenLines.clear()
        for (i in 0 until rows) {
            screenLines.add(TerminalLine(columns))
        }
    }

    fun resize(newColumns: Int, newRows: Int) {
        val oldColumns = columns
        val oldRows = rows

        columns = newColumns
        rows = newRows
        scrollBottom = rows - 1

        // Adjust screen lines
        while (screenLines.size < rows) {
            screenLines.add(TerminalLine(columns))
        }
        while (screenLines.size > rows) {
            val removed = screenLines.removeAt(0)
            addToScrollback(removed)
        }

        // Resize each line
        for (line in screenLines) {
            while (line.cells.size < columns) {
                line.cells.add(TerminalCell.EMPTY)
            }
            while (line.cells.size > columns) {
                line.cells.removeAt(line.cells.lastIndex)
            }
        }

        // Clamp cursor position
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorColumn = cursorColumn.coerceIn(0, columns - 1)
    }

    fun getLine(row: Int): TerminalLine? {
        return if (row in screenLines.indices) screenLines[row] else null
    }

    fun getCell(row: Int, column: Int): TerminalCell {
        return getLine(row)?.getCell(column) ?: TerminalCell.EMPTY
    }

    fun setCell(row: Int, column: Int, cell: TerminalCell) {
        getLine(row)?.setCell(column, cell)
    }

    /**
     * Write a character at the current cursor position.
     */
    fun writeChar(char: Char) {
        if (cursorColumn >= columns) {
            if (autoWrapMode) {
                getLine(cursorRow)?.wrapped = true
                carriageReturn()
                lineFeed()
            } else {
                cursorColumn = columns - 1
            }
        }

        setCell(cursorRow, cursorColumn, TerminalCell(char, currentStyle))
        cursorColumn++
    }

    /**
     * Move cursor to a specific position.
     */
    fun setCursorPosition(row: Int, column: Int) {
        val effectiveRow = if (originMode) {
            (row + scrollTop).coerceIn(scrollTop, scrollBottom)
        } else {
            row.coerceIn(0, rows - 1)
        }
        cursorRow = effectiveRow
        cursorColumn = column.coerceIn(0, columns - 1)
    }

    fun moveCursorUp(count: Int = 1) {
        val minRow = if (originMode) scrollTop else 0
        cursorRow = (cursorRow - count).coerceAtLeast(minRow)
    }

    fun moveCursorDown(count: Int = 1) {
        val maxRow = if (originMode) scrollBottom else rows - 1
        cursorRow = (cursorRow + count).coerceAtMost(maxRow)
    }

    fun moveCursorForward(count: Int = 1) {
        cursorColumn = (cursorColumn + count).coerceAtMost(columns - 1)
    }

    fun moveCursorBackward(count: Int = 1) {
        cursorColumn = (cursorColumn - count).coerceAtLeast(0)
    }

    fun carriageReturn() {
        cursorColumn = 0
    }

    fun lineFeed() {
        if (cursorRow == scrollBottom) {
            scrollUp(1)
        } else if (cursorRow < rows - 1) {
            cursorRow++
        }
    }

    fun reverseLineFeed() {
        if (cursorRow == scrollTop) {
            scrollDown(1)
        } else if (cursorRow > 0) {
            cursorRow--
        }
    }

    fun tab() {
        // Move to next tab stop (every 8 columns)
        val nextTab = ((cursorColumn / 8) + 1) * 8
        cursorColumn = nextTab.coerceAtMost(columns - 1)
    }

    fun backspace() {
        if (cursorColumn > 0) {
            cursorColumn--
        }
    }

    /**
     * Scroll the screen up by the given number of lines.
     */
    fun scrollUp(lines: Int = 1) {
        for (i in 0 until lines) {
            if (scrollTop < screenLines.size) {
                val removed = screenLines.removeAt(scrollTop)
                addToScrollback(removed)
                screenLines.add(scrollBottom, TerminalLine(columns))
            }
        }
    }

    /**
     * Scroll the screen down by the given number of lines.
     */
    fun scrollDown(lines: Int = 1) {
        for (i in 0 until lines) {
            if (scrollBottom < screenLines.size) {
                screenLines.removeAt(scrollBottom)
                screenLines.add(scrollTop, TerminalLine(columns))
            }
        }
    }

    private fun addToScrollback(line: TerminalLine) {
        scrollbackLines.add(line)
        while (scrollbackLines.size > maxScrollback) {
            scrollbackLines.removeAt(0)
        }
    }

    fun setScrollRegion(top: Int, bottom: Int) {
        scrollTop = top.coerceIn(0, rows - 1)
        scrollBottom = bottom.coerceIn(scrollTop, rows - 1)
        setCursorPosition(0, 0)
    }

    fun resetScrollRegion() {
        scrollTop = 0
        scrollBottom = rows - 1
    }

    fun saveCursor() {
        savedCursorRow = cursorRow
        savedCursorColumn = cursorColumn
        savedStyle = currentStyle
    }

    fun restoreCursor() {
        cursorRow = savedCursorRow.coerceIn(0, rows - 1)
        cursorColumn = savedCursorColumn.coerceIn(0, columns - 1)
        currentStyle = savedStyle
    }

    /**
     * Erase in display.
     */
    fun eraseInDisplay(mode: Int) {
        when (mode) {
            0 -> { // Erase from cursor to end of screen
                eraseInLine(0)
                for (i in (cursorRow + 1) until rows) {
                    getLine(i)?.clear(currentStyle)
                }
            }
            1 -> { // Erase from start of screen to cursor
                for (i in 0 until cursorRow) {
                    getLine(i)?.clear(currentStyle)
                }
                eraseInLine(1)
            }
            2, 3 -> { // Erase entire screen (3 also clears scrollback)
                for (i in 0 until rows) {
                    getLine(i)?.clear(currentStyle)
                }
                if (mode == 3) {
                    scrollbackLines.clear()
                }
            }
        }
    }

    /**
     * Erase in line.
     */
    fun eraseInLine(mode: Int) {
        val line = getLine(cursorRow) ?: return
        when (mode) {
            0 -> line.clearRange(cursorColumn, columns, currentStyle) // Cursor to end
            1 -> line.clearRange(0, cursorColumn + 1, currentStyle) // Start to cursor
            2 -> line.clear(currentStyle) // Entire line
        }
    }

    /**
     * Delete characters at cursor position.
     */
    fun deleteCharacters(count: Int) {
        val line = getLine(cursorRow) ?: return
        val deleteCount = count.coerceAtMost(columns - cursorColumn)
        for (i in 0 until deleteCount) {
            if (cursorColumn < line.cells.size) {
                line.cells.removeAt(cursorColumn)
                line.cells.add(TerminalCell(' ', currentStyle))
            }
        }
    }

    /**
     * Insert blank characters at cursor position.
     */
    fun insertCharacters(count: Int) {
        val line = getLine(cursorRow) ?: return
        val insertCount = count.coerceAtMost(columns - cursorColumn)
        for (i in 0 until insertCount) {
            line.cells.add(cursorColumn, TerminalCell(' ', currentStyle))
            if (line.cells.size > columns) {
                line.cells.removeAt(line.cells.lastIndex)
            }
        }
    }

    /**
     * Insert blank lines at cursor position.
     */
    fun insertLines(count: Int) {
        if (cursorRow !in scrollTop..scrollBottom) return
        val insertCount = count.coerceAtMost(scrollBottom - cursorRow + 1)
        for (i in 0 until insertCount) {
            if (scrollBottom < screenLines.size) {
                screenLines.removeAt(scrollBottom)
            }
            screenLines.add(cursorRow, TerminalLine(columns))
        }
    }

    /**
     * Delete lines at cursor position.
     */
    fun deleteLines(count: Int) {
        if (cursorRow !in scrollTop..scrollBottom) return
        val deleteCount = count.coerceAtMost(scrollBottom - cursorRow + 1)
        for (i in 0 until deleteCount) {
            if (cursorRow < screenLines.size) {
                screenLines.removeAt(cursorRow)
                screenLines.add(scrollBottom, TerminalLine(columns))
            }
        }
    }

    /**
     * Reset terminal to initial state.
     */
    fun reset() {
        initializeScreen()
        cursorRow = 0
        cursorColumn = 0
        currentStyle = CellStyle.DEFAULT
        scrollTop = 0
        scrollBottom = rows - 1
        originMode = false
        autoWrapMode = true
        insertMode = false
        cursorVisible = true
        scrollbackLines.clear()
    }

    /**
     * Get all screen lines for rendering.
     */
    fun getScreenContent(): List<TerminalLine> = screenLines.toList()

    /**
     * Get scrollback lines.
     */
    fun getScrollbackContent(): List<TerminalLine> = scrollbackLines.toList()

    /**
     * Get total scrollback size.
     */
    fun getScrollbackSize(): Int = scrollbackLines.size
}
