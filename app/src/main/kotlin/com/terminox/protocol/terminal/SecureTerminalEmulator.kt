package com.terminox.protocol.terminal

import com.terminox.security.EncryptedScrollbackBuffer
import com.terminox.security.EncryptedScrollbackBufferFactory
import com.terminox.security.RetentionPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Extended terminal state that includes security information.
 */
data class SecureTerminalState(
    val lines: List<TerminalLine> = emptyList(),
    val cursorRow: Int = 0,
    val cursorColumn: Int = 0,
    val cursorVisible: Boolean = true,
    val columns: Int = 80,
    val rows: Int = 24,
    val scrollbackSize: Int = 0,
    val isScrollbackEncrypted: Boolean = true
)

/**
 * Secure terminal emulator with encrypted scrollback buffer.
 *
 * This emulator provides the same functionality as TerminalEmulator but stores
 * scrollback history in an encrypted format. Each session has its own encryption
 * key stored in Android Keystore.
 *
 * Security features:
 * - AES-256-GCM encrypted scrollback buffer
 * - Per-session encryption keys
 * - Configurable retention policies
 * - Secure memory wiping on session close
 * - No plaintext scrollback data in memory or logs
 *
 * @param sessionId Unique identifier for this terminal session (used for key management)
 * @param bufferFactory Factory for creating encrypted scrollback buffers
 * @param retentionPolicy Configuration for scrollback retention and cleanup
 * @param initialColumns Initial terminal width
 * @param initialRows Initial terminal height
 */
class SecureTerminalEmulator(
    private val sessionId: String,
    private val bufferFactory: EncryptedScrollbackBufferFactory,
    private val retentionPolicy: RetentionPolicy = RetentionPolicy.DEFAULT,
    initialColumns: Int = 80,
    initialRows: Int = 24
) {
    private val encryptedScrollback: EncryptedScrollbackBuffer =
        bufferFactory.createBuffer(sessionId, retentionPolicy)

    private val buffer = SecureTerminalBuffer(
        columns = initialColumns,
        rows = initialRows,
        encryptedScrollback = encryptedScrollback
    )

    private val parser = SecureAnsiParser(buffer)

    private val _state = MutableStateFlow(createState())
    val state: StateFlow<SecureTerminalState> = _state.asStateFlow()

    val columns: Int get() = buffer.columns
    val rows: Int get() = buffer.rows
    val cursorRow: Int get() = buffer.cursorRow
    val cursorColumn: Int get() = buffer.cursorColumn

    private var isDestroyed = false

    /**
     * Process incoming data from the terminal.
     */
    fun processInput(data: ByteArray) {
        checkNotDestroyed()
        parser.process(data)
        updateState()
    }

    /**
     * Process a string input.
     */
    fun processInput(text: String) {
        checkNotDestroyed()
        parser.process(text)
        updateState()
    }

    /**
     * Resize the terminal.
     */
    fun resize(columns: Int, rows: Int) {
        checkNotDestroyed()
        buffer.resize(columns, rows)
        updateState()
    }

    /**
     * Reset the terminal to initial state.
     */
    fun reset() {
        checkNotDestroyed()
        buffer.reset()
        updateState()
    }

    /**
     * Get the current screen content.
     */
    fun getScreenContent(): List<TerminalLine> {
        checkNotDestroyed()
        return buffer.getScreenContent()
    }

    /**
     * Get a specific line from the screen.
     */
    fun getLine(row: Int): TerminalLine? {
        checkNotDestroyed()
        return buffer.getLine(row)
    }

    /**
     * Get a specific cell.
     */
    fun getCell(row: Int, column: Int): TerminalCell {
        checkNotDestroyed()
        return buffer.getCell(row, column)
    }

    /**
     * Get the scrollback buffer content (decrypted on demand).
     * Returns the decrypted scrollback lines.
     */
    fun getScrollbackContent(): List<String> {
        checkNotDestroyed()
        return buffer.getScrollbackContent()
    }

    /**
     * Get the total number of lines in scrollback.
     */
    fun getScrollbackSize(): Int {
        checkNotDestroyed()
        return buffer.getScrollbackSize()
    }

    /**
     * Check if cursor is visible.
     */
    fun isCursorVisible(): Boolean {
        checkNotDestroyed()
        return buffer.cursorVisible
    }

    /**
     * Manually trigger retention policy enforcement.
     * Useful for periodic cleanup of old scrollback data.
     */
    fun enforceRetention() {
        checkNotDestroyed()
        encryptedScrollback.enforceRetention()
        updateState()
    }

    /**
     * Securely destroys the terminal emulator and all associated data.
     *
     * This operation:
     * - Wipes all screen content
     * - Destroys encrypted scrollback buffer
     * - Deletes session encryption key from Android Keystore
     *
     * After calling destroy(), any further operations will throw IllegalStateException.
     */
    fun destroy() {
        if (isDestroyed) return

        buffer.destroy()
        isDestroyed = true
    }

    /**
     * Checks if the emulator has been destroyed.
     */
    fun isDestroyed(): Boolean = isDestroyed

    private fun createState(): SecureTerminalState {
        return SecureTerminalState(
            lines = buffer.getScreenContent(),
            cursorRow = buffer.cursorRow,
            cursorColumn = buffer.cursorColumn,
            cursorVisible = buffer.cursorVisible,
            columns = buffer.columns,
            rows = buffer.rows,
            scrollbackSize = buffer.getScrollbackSize(),
            isScrollbackEncrypted = true
        )
    }

    private fun updateState() {
        _state.value = createState()
    }

    private fun checkNotDestroyed() {
        if (isDestroyed) {
            throw IllegalStateException("SecureTerminalEmulator has been destroyed")
        }
    }
}

/**
 * ANSI parser adapted for SecureTerminalBuffer.
 * This is a thin wrapper that delegates to the existing AnsiParser logic
 * but works with SecureTerminalBuffer instead of TerminalBuffer.
 */
internal class SecureAnsiParser(private val buffer: SecureTerminalBuffer) {

    private var state = ParserState.GROUND
    private val params = mutableListOf<Int>()
    private val intermediate = StringBuilder()
    private val oscString = StringBuilder()

    private enum class ParserState {
        GROUND,
        ESCAPE,
        ESCAPE_INTERMEDIATE,
        CSI_ENTRY,
        CSI_PARAM,
        CSI_INTERMEDIATE,
        OSC_STRING,
        DCS_ENTRY,
        SOS_PM_APC_STRING
    }

    fun process(data: ByteArray) {
        for (byte in data) {
            processByte(byte.toInt() and 0xFF)
        }
    }

    fun process(text: String) {
        for (char in text) {
            processByte(char.code)
        }
    }

    private fun processByte(byte: Int) {
        when (state) {
            ParserState.GROUND -> processGround(byte)
            ParserState.ESCAPE -> processEscape(byte)
            ParserState.ESCAPE_INTERMEDIATE -> processEscapeIntermediate(byte)
            ParserState.CSI_ENTRY -> processCsiEntry(byte)
            ParserState.CSI_PARAM -> processCsiParam(byte)
            ParserState.CSI_INTERMEDIATE -> processCsiIntermediate(byte)
            ParserState.OSC_STRING -> processOscString(byte)
            ParserState.DCS_ENTRY -> processDcsEntry(byte)
            ParserState.SOS_PM_APC_STRING -> processSosPmApcString(byte)
        }
    }

    private fun processGround(byte: Int) {
        when (byte) {
            0x1B -> {
                state = ParserState.ESCAPE
            }
            0x0D -> buffer.carriageReturn()
            0x0A, 0x0B, 0x0C -> buffer.lineFeed()
            0x09 -> buffer.tab()
            0x08 -> buffer.backspace()
            0x07 -> { /* Bell - ignore */ }
            in 0x20..0x7E -> buffer.writeChar(byte.toChar())
            in 0x80..0xFF -> buffer.writeChar(byte.toChar())
        }
    }

    private fun processEscape(byte: Int) {
        when (byte) {
            0x5B -> {
                state = ParserState.CSI_ENTRY
                params.clear()
                intermediate.clear()
            }
            0x5D -> {
                state = ParserState.OSC_STRING
                oscString.clear()
            }
            0x50 -> {
                state = ParserState.DCS_ENTRY
            }
            0x58, 0x5E, 0x5F -> {
                state = ParserState.SOS_PM_APC_STRING
            }
            in 0x20..0x2F -> {
                state = ParserState.ESCAPE_INTERMEDIATE
                intermediate.append(byte.toChar())
            }
            in 0x30..0x7E -> {
                executeEscapeSequence(byte.toChar())
                state = ParserState.GROUND
            }
            else -> state = ParserState.GROUND
        }
    }

    private fun processEscapeIntermediate(byte: Int) {
        when (byte) {
            in 0x20..0x2F -> intermediate.append(byte.toChar())
            in 0x30..0x7E -> {
                executeEscapeSequence(byte.toChar())
                state = ParserState.GROUND
            }
            else -> state = ParserState.GROUND
        }
    }

    private fun processCsiEntry(byte: Int) {
        when (byte) {
            in 0x30..0x39 -> {
                state = ParserState.CSI_PARAM
                params.add(byte - 0x30)
            }
            0x3B -> {
                state = ParserState.CSI_PARAM
                params.add(0)
            }
            in 0x3C..0x3F -> {
                state = ParserState.CSI_PARAM
                intermediate.append(byte.toChar())
            }
            in 0x20..0x2F -> {
                state = ParserState.CSI_INTERMEDIATE
                intermediate.append(byte.toChar())
            }
            in 0x40..0x7E -> {
                executeCsiSequence(byte.toChar())
                state = ParserState.GROUND
            }
            else -> state = ParserState.GROUND
        }
    }

    private fun processCsiParam(byte: Int) {
        when (byte) {
            in 0x30..0x39 -> {
                val lastIndex = params.lastIndex
                if (lastIndex >= 0) {
                    params[lastIndex] = params[lastIndex] * 10 + (byte - 0x30)
                }
            }
            0x3B -> params.add(0)
            in 0x3C..0x3F -> intermediate.append(byte.toChar())
            in 0x20..0x2F -> {
                state = ParserState.CSI_INTERMEDIATE
                intermediate.append(byte.toChar())
            }
            in 0x40..0x7E -> {
                executeCsiSequence(byte.toChar())
                state = ParserState.GROUND
            }
            else -> state = ParserState.GROUND
        }
    }

    private fun processCsiIntermediate(byte: Int) {
        when (byte) {
            in 0x20..0x2F -> intermediate.append(byte.toChar())
            in 0x40..0x7E -> {
                executeCsiSequence(byte.toChar())
                state = ParserState.GROUND
            }
            else -> state = ParserState.GROUND
        }
    }

    private fun processOscString(byte: Int) {
        when (byte) {
            0x07 -> {
                executeOscSequence()
                state = ParserState.GROUND
            }
            0x1B -> state = ParserState.ESCAPE
            0x9C -> {
                executeOscSequence()
                state = ParserState.GROUND
            }
            else -> oscString.append(byte.toChar())
        }
    }

    private fun processDcsEntry(byte: Int) {
        when (byte) {
            0x9C, 0x1B -> state = ParserState.GROUND
            else -> { /* Collect DCS data */ }
        }
    }

    private fun processSosPmApcString(byte: Int) {
        when (byte) {
            0x9C, 0x1B -> state = ParserState.GROUND
            else -> { /* Collect string data */ }
        }
    }

    private fun executeEscapeSequence(finalByte: Char) {
        when (finalByte) {
            'M' -> buffer.reverseLineFeed()
            '7' -> buffer.saveCursor()
            '8' -> buffer.restoreCursor()
            'c' -> buffer.reset()
            'D' -> buffer.lineFeed()
            'E' -> {
                buffer.carriageReturn()
                buffer.lineFeed()
            }
        }
    }

    private fun executeCsiSequence(finalByte: Char) {
        val param1 = params.getOrElse(0) { 0 }
        val param2 = params.getOrElse(1) { 0 }

        when (finalByte) {
            'A' -> buffer.moveCursorUp(maxOf(1, param1))
            'B' -> buffer.moveCursorDown(maxOf(1, param1))
            'C' -> buffer.moveCursorForward(maxOf(1, param1))
            'D' -> buffer.moveCursorBackward(maxOf(1, param1))
            'H', 'f' -> buffer.setCursorPosition(
                (if (param1 == 0) 1 else param1) - 1,
                (if (param2 == 0) 1 else param2) - 1
            )
            'J' -> buffer.eraseInDisplay(param1)
            'K' -> buffer.eraseInLine(param1)
            'L' -> buffer.insertLines(maxOf(1, param1))
            'M' -> buffer.deleteLines(maxOf(1, param1))
            'P' -> buffer.deleteCharacters(maxOf(1, param1))
            '@' -> buffer.insertCharacters(maxOf(1, param1))
            'r' -> {
                val top = if (param1 == 0) 1 else param1
                val bottom = if (param2 == 0) buffer.rows else param2
                buffer.setScrollRegion(top - 1, bottom - 1)
            }
            's' -> buffer.saveCursor()
            'u' -> buffer.restoreCursor()
            'm' -> executeSgr()
            'h' -> executeSetMode()
            'l' -> executeResetMode()
        }
    }

    private fun executeSgr() {
        if (params.isEmpty()) {
            buffer.currentStyle = CellStyle.DEFAULT
            return
        }

        var style = buffer.currentStyle
        var i = 0
        while (i < params.size) {
            when (val param = params[i]) {
                0 -> style = CellStyle.DEFAULT
                1 -> style = style.copy(attributes = style.attributes.copy(bold = true))
                2 -> style = style.copy(attributes = style.attributes.copy(dim = true))
                3 -> style = style.copy(attributes = style.attributes.copy(italic = true))
                4 -> style = style.copy(attributes = style.attributes.copy(underline = true))
                5, 6 -> style = style.copy(attributes = style.attributes.copy(blink = true))
                7 -> style = style.copy(attributes = style.attributes.copy(inverse = true))
                8 -> style = style.copy(attributes = style.attributes.copy(hidden = true))
                9 -> style = style.copy(attributes = style.attributes.copy(strikethrough = true))
                22 -> style = style.copy(attributes = style.attributes.copy(bold = false, dim = false))
                23 -> style = style.copy(attributes = style.attributes.copy(italic = false))
                24 -> style = style.copy(attributes = style.attributes.copy(underline = false))
                25 -> style = style.copy(attributes = style.attributes.copy(blink = false))
                27 -> style = style.copy(attributes = style.attributes.copy(inverse = false))
                28 -> style = style.copy(attributes = style.attributes.copy(hidden = false))
                29 -> style = style.copy(attributes = style.attributes.copy(strikethrough = false))
                in 30..37 -> style = style.copy(foreground = TerminalColor.Ansi(param - 30))
                38 -> {
                    if (i + 2 < params.size && params[i + 1] == 5) {
                        style = style.copy(foreground = TerminalColor.Indexed(params[i + 2]))
                        i += 2
                    } else if (i + 4 < params.size && params[i + 1] == 2) {
                        style = style.copy(foreground = TerminalColor.Rgb(
                            params[i + 2], params[i + 3], params[i + 4]
                        ))
                        i += 4
                    }
                }
                39 -> style = style.copy(foreground = TerminalColor.Default)
                in 40..47 -> style = style.copy(background = TerminalColor.Ansi(param - 40))
                48 -> {
                    if (i + 2 < params.size && params[i + 1] == 5) {
                        style = style.copy(background = TerminalColor.Indexed(params[i + 2]))
                        i += 2
                    } else if (i + 4 < params.size && params[i + 1] == 2) {
                        style = style.copy(background = TerminalColor.Rgb(
                            params[i + 2], params[i + 3], params[i + 4]
                        ))
                        i += 4
                    }
                }
                49 -> style = style.copy(background = TerminalColor.Default)
                in 90..97 -> style = style.copy(foreground = TerminalColor.Ansi(param - 90 + 8))
                in 100..107 -> style = style.copy(background = TerminalColor.Ansi(param - 100 + 8))
            }
            i++
        }
        buffer.currentStyle = style
    }

    private fun executeSetMode() {
        if (intermediate.toString() == "?") {
            when (params.getOrElse(0) { 0 }) {
                6 -> buffer.originMode = true
                7 -> buffer.autoWrapMode = true
                25 -> buffer.cursorVisible = true
            }
        } else {
            when (params.getOrElse(0) { 0 }) {
                4 -> buffer.insertMode = true
            }
        }
    }

    private fun executeResetMode() {
        if (intermediate.toString() == "?") {
            when (params.getOrElse(0) { 0 }) {
                6 -> buffer.originMode = false
                7 -> buffer.autoWrapMode = false
                25 -> buffer.cursorVisible = false
            }
        } else {
            when (params.getOrElse(0) { 0 }) {
                4 -> buffer.insertMode = false
            }
        }
    }

    private fun executeOscSequence() {
        // OSC sequences like window title - currently ignored for security
        // (don't expose terminal titles which might contain sensitive info)
    }
}
