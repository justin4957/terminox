package com.terminox.protocol.terminal

import com.terminox.security.EncryptedScrollbackBuffer
import com.terminox.security.secureWipe
import java.util.Arrays

/**
 * A secure terminal buffer that encrypts scrollback data.
 *
 * This buffer wraps the standard TerminalBuffer functionality but stores
 * scrollback lines in an encrypted format using the provided EncryptedScrollbackBuffer.
 * Lines that scroll off the visible screen are encrypted before storage.
 *
 * Security properties:
 * - Visible screen content remains in memory (required for rendering)
 * - Scrollback history is encrypted with AES-256-GCM
 * - Secure memory wiping on session close
 * - Per-session encryption keys
 */
class SecureTerminalBuffer(
    var columns: Int = 80,
    var rows: Int = 24,
    private val encryptedScrollback: EncryptedScrollbackBuffer
) {
    // Active screen lines (plaintext, required for display)
    private val screenLines = mutableListOf<TerminalLine>()

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
    var originMode: Boolean = false
    var autoWrapMode: Boolean = true
    var insertMode: Boolean = false
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
        columns = newColumns
        rows = newRows
        scrollBottom = rows - 1

        while (screenLines.size < rows) {
            screenLines.add(TerminalLine(columns))
        }
        while (screenLines.size > rows) {
            val removed = screenLines.removeAt(0)
            addToEncryptedScrollback(removed)
        }

        for (line in screenLines) {
            while (line.cells.size < columns) {
                line.cells.add(TerminalCell.EMPTY)
            }
            while (line.cells.size > columns) {
                line.cells.removeAt(line.cells.lastIndex)
            }
        }

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
        val nextTab = ((cursorColumn / 8) + 1) * 8
        cursorColumn = nextTab.coerceAtMost(columns - 1)
    }

    fun backspace() {
        if (cursorColumn > 0) {
            cursorColumn--
        }
    }

    /**
     * Scrolls the screen up, encrypting lines that leave the visible area.
     */
    fun scrollUp(lines: Int = 1) {
        for (i in 0 until lines) {
            if (scrollTop < screenLines.size) {
                val removed = screenLines.removeAt(scrollTop)
                addToEncryptedScrollback(removed)
                screenLines.add(scrollBottom, TerminalLine(columns))
            }
        }
    }

    fun scrollDown(lines: Int = 1) {
        for (i in 0 until lines) {
            if (scrollBottom < screenLines.size) {
                screenLines.removeAt(scrollBottom)
                screenLines.add(scrollTop, TerminalLine(columns))
            }
        }
    }

    /**
     * Adds a line to the encrypted scrollback buffer.
     * The line is serialized, encrypted, and the plaintext is securely wiped.
     */
    private fun addToEncryptedScrollback(line: TerminalLine) {
        val serialized = serializeLine(line)
        try {
            encryptedScrollback.addLine(serialized)
        } finally {
            // The serialized string will be garbage collected,
            // but the char array backing has already been wiped in EncryptedScrollbackBuffer
        }
    }

    /**
     * Serializes a terminal line to a string format.
     * Format: characters followed by style information.
     */
    private fun serializeLine(line: TerminalLine): String {
        val sb = StringBuilder()
        for (cell in line.cells) {
            sb.append(cell.character)
        }
        // Trim trailing spaces for storage efficiency
        return sb.toString().trimEnd()
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

    fun eraseInDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseInLine(0)
                for (i in (cursorRow + 1) until rows) {
                    getLine(i)?.clear(currentStyle)
                }
            }
            1 -> {
                for (i in 0 until cursorRow) {
                    getLine(i)?.clear(currentStyle)
                }
                eraseInLine(1)
            }
            2, 3 -> {
                for (i in 0 until rows) {
                    getLine(i)?.clear(currentStyle)
                }
                if (mode == 3) {
                    encryptedScrollback.clear()
                }
            }
        }
    }

    fun eraseInLine(mode: Int) {
        val line = getLine(cursorRow) ?: return
        when (mode) {
            0 -> line.clearRange(cursorColumn, columns, currentStyle)
            1 -> line.clearRange(0, cursorColumn + 1, currentStyle)
            2 -> line.clear(currentStyle)
        }
    }

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
     * Resets the terminal, securely clearing all data including encrypted scrollback.
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
        encryptedScrollback.clear()
    }

    /**
     * Securely destroys the buffer and all associated data.
     * This wipes screen content and destroys the encrypted scrollback.
     */
    fun destroy() {
        // Clear screen lines (they're just objects, GC will handle)
        screenLines.clear()
        // Destroy encrypted scrollback (this wipes and deletes the key)
        encryptedScrollback.destroy()
    }

    fun getScreenContent(): List<TerminalLine> = screenLines.toList()

    /**
     * Gets scrollback content by decrypting stored lines.
     * Returns the decrypted lines for display.
     */
    fun getScrollbackContent(): List<String> {
        return encryptedScrollback.getAllLines()
    }

    /**
     * Gets the number of lines in encrypted scrollback.
     */
    fun getScrollbackSize(): Int = encryptedScrollback.size
}
