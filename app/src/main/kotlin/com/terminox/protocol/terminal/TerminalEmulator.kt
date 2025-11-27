package com.terminox.protocol.terminal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Terminal state for UI observation.
 */
data class TerminalState(
    val lines: List<TerminalLine> = emptyList(),
    val cursorRow: Int = 0,
    val cursorColumn: Int = 0,
    val cursorVisible: Boolean = true,
    val columns: Int = 80,
    val rows: Int = 24,
    val scrollbackSize: Int = 0
)

/**
 * Terminal emulator that processes input and maintains screen state.
 * Provides observable state for the UI layer.
 */
class TerminalEmulator(
    initialColumns: Int = 80,
    initialRows: Int = 24
) {
    private val buffer = TerminalBuffer(initialColumns, initialRows)
    private val parser = AnsiParser(buffer)

    private val _state = MutableStateFlow(createState())
    val state: StateFlow<TerminalState> = _state.asStateFlow()

    val columns: Int get() = buffer.columns
    val rows: Int get() = buffer.rows
    val cursorRow: Int get() = buffer.cursorRow
    val cursorColumn: Int get() = buffer.cursorColumn

    /**
     * Process incoming data from the terminal.
     */
    fun processInput(data: ByteArray) {
        parser.process(data)
        updateState()
    }

    /**
     * Process a string input.
     */
    fun processInput(text: String) {
        parser.process(text)
        updateState()
    }

    /**
     * Resize the terminal.
     */
    fun resize(columns: Int, rows: Int) {
        buffer.resize(columns, rows)
        updateState()
    }

    /**
     * Reset the terminal to initial state.
     */
    fun reset() {
        buffer.reset()
        updateState()
    }

    /**
     * Get the current screen content.
     */
    fun getScreenContent(): List<TerminalLine> {
        return buffer.getScreenContent()
    }

    /**
     * Get a specific line from the screen.
     */
    fun getLine(row: Int): TerminalLine? {
        return buffer.getLine(row)
    }

    /**
     * Get a specific cell.
     */
    fun getCell(row: Int, column: Int): TerminalCell {
        return buffer.getCell(row, column)
    }

    /**
     * Get the scrollback buffer content.
     */
    fun getScrollbackContent(): List<TerminalLine> {
        return buffer.getScrollbackContent()
    }

    /**
     * Get the total number of lines in scrollback.
     */
    fun getScrollbackSize(): Int {
        return buffer.getScrollbackSize()
    }

    /**
     * Check if cursor is visible.
     */
    fun isCursorVisible(): Boolean {
        return buffer.cursorVisible
    }

    private fun createState(): TerminalState {
        return TerminalState(
            lines = buffer.getScreenContent(),
            cursorRow = buffer.cursorRow,
            cursorColumn = buffer.cursorColumn,
            cursorVisible = buffer.cursorVisible,
            columns = buffer.columns,
            rows = buffer.rows,
            scrollbackSize = buffer.getScrollbackSize()
        )
    }

    private fun updateState() {
        _state.value = createState()
    }
}
