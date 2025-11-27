package com.terminox.protocol.terminal

/**
 * ANSI/VT100 escape sequence parser.
 * Implements a state machine to parse terminal escape sequences.
 */
class AnsiParser(
    private val buffer: TerminalBuffer
) {
    private var state: ParserState = ParserState.Ground
    private val params = mutableListOf<Int>()
    private val intermediates = StringBuilder()
    private var privateMarker: Char? = null

    private enum class ParserState {
        Ground,
        Escape,
        EscapeIntermediate,
        CsiEntry,
        CsiParam,
        CsiIntermediate,
        OscString,
        DcsEntry,
        SosPmApcString
    }

    /**
     * Process input bytes and update the terminal buffer.
     */
    fun process(data: ByteArray) {
        for (byte in data) {
            processChar(byte.toInt().toChar())
        }
    }

    /**
     * Process a string and update the terminal buffer.
     */
    fun process(text: String) {
        for (char in text) {
            processChar(char)
        }
    }

    private fun processChar(char: Char) {
        when (state) {
            ParserState.Ground -> handleGround(char)
            ParserState.Escape -> handleEscape(char)
            ParserState.EscapeIntermediate -> handleEscapeIntermediate(char)
            ParserState.CsiEntry -> handleCsiEntry(char)
            ParserState.CsiParam -> handleCsiParam(char)
            ParserState.CsiIntermediate -> handleCsiIntermediate(char)
            ParserState.OscString -> handleOscString(char)
            ParserState.DcsEntry -> handleDcsEntry(char)
            ParserState.SosPmApcString -> handleSosPmApcString(char)
        }
    }

    private fun handleGround(char: Char) {
        when {
            char == '\u001b' -> { // ESC
                state = ParserState.Escape
            }
            char == '\r' -> buffer.carriageReturn()
            char == '\n' || char == '\u000B' || char == '\u000C' -> buffer.lineFeed()
            char == '\t' -> buffer.tab()
            char == '\b' -> buffer.backspace()
            char == '\u0007' -> { /* Bell - ignore or trigger callback */ }
            char == '\u000E' -> { /* Shift Out - switch to G1 charset */ }
            char == '\u000F' -> { /* Shift In - switch to G0 charset */ }
            char >= ' ' -> buffer.writeChar(char)
        }
    }

    private fun handleEscape(char: Char) {
        when (char) {
            '[' -> {
                state = ParserState.CsiEntry
                params.clear()
                intermediates.clear()
                privateMarker = null
            }
            ']' -> {
                state = ParserState.OscString
                params.clear()
            }
            'P' -> {
                state = ParserState.DcsEntry
            }
            '^', '_', 'X' -> {
                state = ParserState.SosPmApcString
            }
            in ' '..'/' -> {
                intermediates.append(char)
                state = ParserState.EscapeIntermediate
            }
            'D' -> { // Index (IND)
                buffer.lineFeed()
                state = ParserState.Ground
            }
            'E' -> { // Next Line (NEL)
                buffer.carriageReturn()
                buffer.lineFeed()
                state = ParserState.Ground
            }
            'M' -> { // Reverse Index (RI)
                buffer.reverseLineFeed()
                state = ParserState.Ground
            }
            '7' -> { // Save Cursor (DECSC)
                buffer.saveCursor()
                state = ParserState.Ground
            }
            '8' -> { // Restore Cursor (DECRC)
                buffer.restoreCursor()
                state = ParserState.Ground
            }
            'c' -> { // Reset (RIS)
                buffer.reset()
                state = ParserState.Ground
            }
            '=' -> { // Application Keypad (DECKPAM)
                state = ParserState.Ground
            }
            '>' -> { // Normal Keypad (DECKPNM)
                state = ParserState.Ground
            }
            else -> state = ParserState.Ground
        }
    }

    private fun handleEscapeIntermediate(char: Char) {
        when {
            char in ' '..'/' -> intermediates.append(char)
            char in '0'..'~' -> {
                // Execute escape sequence with intermediates
                state = ParserState.Ground
            }
            else -> state = ParserState.Ground
        }
    }

    private fun handleCsiEntry(char: Char) {
        when {
            char in '0'..'9' -> {
                params.add(char - '0')
                state = ParserState.CsiParam
            }
            char == ';' -> {
                params.add(0)
                state = ParserState.CsiParam
            }
            char == '?' || char == '>' || char == '!' -> {
                privateMarker = char
                state = ParserState.CsiParam
            }
            char in ' '..'/' -> {
                intermediates.append(char)
                state = ParserState.CsiIntermediate
            }
            char in '@'..'~' -> {
                executeCsi(char)
                state = ParserState.Ground
            }
            else -> state = ParserState.Ground
        }
    }

    private fun handleCsiParam(char: Char) {
        when {
            char in '0'..'9' -> {
                val lastIndex = params.lastIndex
                if (lastIndex >= 0) {
                    params[lastIndex] = params[lastIndex] * 10 + (char - '0')
                } else {
                    params.add(char - '0')
                }
            }
            char == ';' -> {
                params.add(0)
            }
            char in ' '..'/' -> {
                intermediates.append(char)
                state = ParserState.CsiIntermediate
            }
            char in '@'..'~' -> {
                executeCsi(char)
                state = ParserState.Ground
            }
            else -> state = ParserState.Ground
        }
    }

    private fun handleCsiIntermediate(char: Char) {
        when {
            char in ' '..'/' -> intermediates.append(char)
            char in '@'..'~' -> {
                executeCsi(char)
                state = ParserState.Ground
            }
            else -> state = ParserState.Ground
        }
    }

    private fun handleOscString(char: Char) {
        // OSC sequences end with BEL or ST (ESC \)
        if (char == '\u0007' || char == '\u001b') {
            // Process OSC if needed (title changes, etc.)
            state = if (char == '\u001b') ParserState.Escape else ParserState.Ground
        }
    }

    private fun handleDcsEntry(char: Char) {
        // DCS sequences - skip until ST
        if (char == '\u001b') {
            state = ParserState.Escape
        }
    }

    private fun handleSosPmApcString(char: Char) {
        // Skip until ST
        if (char == '\u001b') {
            state = ParserState.Escape
        }
    }

    private fun executeCsi(finalChar: Char) {
        val p1 = params.getOrElse(0) { 0 }
        val p2 = params.getOrElse(1) { 0 }

        when {
            privateMarker == '?' -> executePrivateCsi(finalChar)
            else -> when (finalChar) {
                'A' -> buffer.moveCursorUp(maxOf(1, p1)) // CUU - Cursor Up
                'B' -> buffer.moveCursorDown(maxOf(1, p1)) // CUD - Cursor Down
                'C' -> buffer.moveCursorForward(maxOf(1, p1)) // CUF - Cursor Forward
                'D' -> buffer.moveCursorBackward(maxOf(1, p1)) // CUB - Cursor Back
                'E' -> { // CNL - Cursor Next Line
                    buffer.moveCursorDown(maxOf(1, p1))
                    buffer.carriageReturn()
                }
                'F' -> { // CPL - Cursor Previous Line
                    buffer.moveCursorUp(maxOf(1, p1))
                    buffer.carriageReturn()
                }
                'G' -> buffer.setCursorPosition(buffer.cursorRow, maxOf(1, p1) - 1) // CHA - Cursor Horizontal Absolute
                'H', 'f' -> { // CUP/HVP - Cursor Position
                    val row = maxOf(1, p1) - 1
                    val col = maxOf(1, p2) - 1
                    buffer.setCursorPosition(row, col)
                }
                'J' -> buffer.eraseInDisplay(p1) // ED - Erase in Display
                'K' -> buffer.eraseInLine(p1) // EL - Erase in Line
                'L' -> buffer.insertLines(maxOf(1, p1)) // IL - Insert Lines
                'M' -> buffer.deleteLines(maxOf(1, p1)) // DL - Delete Lines
                'P' -> buffer.deleteCharacters(maxOf(1, p1)) // DCH - Delete Characters
                '@' -> buffer.insertCharacters(maxOf(1, p1)) // ICH - Insert Characters
                'S' -> buffer.scrollUp(maxOf(1, p1)) // SU - Scroll Up
                'T' -> buffer.scrollDown(maxOf(1, p1)) // SD - Scroll Down
                'd' -> buffer.setCursorPosition(maxOf(1, p1) - 1, buffer.cursorColumn) // VPA - Line Position Absolute
                'm' -> executeSgr() // SGR - Select Graphic Rendition
                'r' -> { // DECSTBM - Set Top and Bottom Margins
                    val top = maxOf(1, p1) - 1
                    val bottom = if (p2 == 0) buffer.rows - 1 else p2 - 1
                    buffer.setScrollRegion(top, bottom)
                }
                's' -> buffer.saveCursor() // SCP - Save Cursor Position
                'u' -> buffer.restoreCursor() // RCP - Restore Cursor Position
                'n' -> { /* DSR - Device Status Report - would need callback */ }
                'c' -> { /* DA - Device Attributes - would need callback */ }
            }
        }
    }

    private fun executePrivateCsi(finalChar: Char) {
        val p1 = params.getOrElse(0) { 0 }

        when (finalChar) {
            'h' -> { // DECSET - DEC Private Mode Set
                when (p1) {
                    1 -> { /* Application Cursor Keys */ }
                    6 -> buffer.originMode = true // DECOM
                    7 -> buffer.autoWrapMode = true // DECAWM
                    25 -> buffer.cursorVisible = true // DECTCEM
                    1049 -> { /* Alternate Screen Buffer */ }
                }
            }
            'l' -> { // DECRST - DEC Private Mode Reset
                when (p1) {
                    1 -> { /* Normal Cursor Keys */ }
                    6 -> buffer.originMode = false
                    7 -> buffer.autoWrapMode = false
                    25 -> buffer.cursorVisible = false
                    1049 -> { /* Normal Screen Buffer */ }
                }
            }
        }
    }

    private fun executeSgr() {
        if (params.isEmpty()) {
            buffer.currentStyle = CellStyle.DEFAULT
            return
        }

        var i = 0
        var style = buffer.currentStyle

        while (i < params.size) {
            when (val code = params[i]) {
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
                in 30..37 -> style = style.copy(foreground = TerminalColor.Ansi(code - 30))
                38 -> {
                    // Extended foreground color
                    val color = parseExtendedColor(i + 1)
                    if (color != null) {
                        style = style.copy(foreground = color.first)
                        i = color.second
                        continue
                    }
                }
                39 -> style = style.copy(foreground = TerminalColor.Default)
                in 40..47 -> style = style.copy(background = TerminalColor.Ansi(code - 40))
                48 -> {
                    // Extended background color
                    val color = parseExtendedColor(i + 1)
                    if (color != null) {
                        style = style.copy(background = color.first)
                        i = color.second
                        continue
                    }
                }
                49 -> style = style.copy(background = TerminalColor.Default)
                in 90..97 -> style = style.copy(foreground = TerminalColor.Ansi(code - 90 + 8))
                in 100..107 -> style = style.copy(background = TerminalColor.Ansi(code - 100 + 8))
            }
            i++
        }

        buffer.currentStyle = style
    }

    private fun parseExtendedColor(startIndex: Int): Pair<TerminalColor, Int>? {
        if (startIndex >= params.size) return null

        return when (params[startIndex]) {
            5 -> {
                // 256-color mode: 38;5;n or 48;5;n
                if (startIndex + 1 < params.size) {
                    Pair(TerminalColor.Indexed(params[startIndex + 1]), startIndex + 2)
                } else null
            }
            2 -> {
                // True color mode: 38;2;r;g;b or 48;2;r;g;b
                if (startIndex + 3 < params.size) {
                    val r = params[startIndex + 1].coerceIn(0, 255)
                    val g = params[startIndex + 2].coerceIn(0, 255)
                    val b = params[startIndex + 3].coerceIn(0, 255)
                    Pair(TerminalColor.Rgb(r, g, b), startIndex + 4)
                } else null
            }
            else -> null
        }
    }
}
