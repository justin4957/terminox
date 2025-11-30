package com.terminox.protocol.terminal

import androidx.compose.ui.graphics.Color
import com.terminox.domain.model.TerminalTheme

/**
 * Represents terminal colors including standard ANSI colors,
 * 256-color palette, and true color (24-bit RGB).
 */
sealed class TerminalColor {
    data object Default : TerminalColor()
    data class Ansi(val code: Int) : TerminalColor()
    data class Indexed(val index: Int) : TerminalColor()
    data class Rgb(val red: Int, val green: Int, val blue: Int) : TerminalColor()

    fun toComposeColor(isDarkTheme: Boolean = true): Color {
        return when (this) {
            is Default -> if (isDarkTheme) Color(0xFFE4E4E4) else Color(0xFF1A1C19)
            is Ansi -> ansiToColor(code, isDarkTheme)
            is Indexed -> indexedToColor(index)
            is Rgb -> Color(red, green, blue)
        }
    }

    /**
     * Convert to Compose Color using the given theme's color palette.
     * This allows terminal text to use the theme's ANSI colors.
     */
    fun toComposeColor(theme: TerminalTheme): Color {
        return when (this) {
            is Default -> theme.foreground
            is Ansi -> theme.getAnsiColor(code)
            is Indexed -> indexedToColor(index, theme)
            is Rgb -> Color(red, green, blue)
        }
    }

    companion object {
        // Standard ANSI colors (normal intensity)
        private val ANSI_COLORS_DARK = arrayOf(
            Color(0xFF000000), // 0: Black
            Color(0xFFCD0000), // 1: Red
            Color(0xFF00CD00), // 2: Green
            Color(0xFFCDCD00), // 3: Yellow
            Color(0xFF0000EE), // 4: Blue
            Color(0xFFCD00CD), // 5: Magenta
            Color(0xFF00CDCD), // 6: Cyan
            Color(0xFFE5E5E5), // 7: White
        )

        // Bright ANSI colors
        private val ANSI_COLORS_BRIGHT = arrayOf(
            Color(0xFF7F7F7F), // 8: Bright Black (Gray)
            Color(0xFFFF0000), // 9: Bright Red
            Color(0xFF00FF00), // 10: Bright Green
            Color(0xFFFFFF00), // 11: Bright Yellow
            Color(0xFF5C5CFF), // 12: Bright Blue
            Color(0xFFFF00FF), // 13: Bright Magenta
            Color(0xFF00FFFF), // 14: Bright Cyan
            Color(0xFFFFFFFF), // 15: Bright White
        )

        private fun ansiToColor(code: Int, isDarkTheme: Boolean): Color {
            return when {
                code in 0..7 -> ANSI_COLORS_DARK[code]
                code in 8..15 -> ANSI_COLORS_BRIGHT[code - 8]
                else -> if (isDarkTheme) Color(0xFFE4E4E4) else Color(0xFF1A1C19)
            }
        }

        private fun indexedToColor(index: Int): Color {
            return when {
                index < 16 -> {
                    // Standard colors
                    if (index < 8) ANSI_COLORS_DARK[index] else ANSI_COLORS_BRIGHT[index - 8]
                }
                index < 232 -> {
                    // 216 color cube (6x6x6)
                    val i = index - 16
                    val r = (i / 36) * 51
                    val g = ((i / 6) % 6) * 51
                    val b = (i % 6) * 51
                    Color(r, g, b)
                }
                else -> {
                    // Grayscale (24 shades)
                    val gray = (index - 232) * 10 + 8
                    Color(gray, gray, gray)
                }
            }
        }

        private fun indexedToColor(index: Int, theme: TerminalTheme): Color {
            return when {
                index < 16 -> {
                    // Use theme's ANSI colors for 0-15
                    theme.getAnsiColor(index)
                }
                index < 232 -> {
                    // 216 color cube (6x6x6) - same as before
                    val i = index - 16
                    val r = (i / 36) * 51
                    val g = ((i / 6) % 6) * 51
                    val b = (i % 6) * 51
                    Color(r, g, b)
                }
                else -> {
                    // Grayscale (24 shades) - same as before
                    val gray = (index - 232) * 10 + 8
                    Color(gray, gray, gray)
                }
            }
        }

        val BLACK = Ansi(0)
        val RED = Ansi(1)
        val GREEN = Ansi(2)
        val YELLOW = Ansi(3)
        val BLUE = Ansi(4)
        val MAGENTA = Ansi(5)
        val CYAN = Ansi(6)
        val WHITE = Ansi(7)
        val BRIGHT_BLACK = Ansi(8)
        val BRIGHT_RED = Ansi(9)
        val BRIGHT_GREEN = Ansi(10)
        val BRIGHT_YELLOW = Ansi(11)
        val BRIGHT_BLUE = Ansi(12)
        val BRIGHT_MAGENTA = Ansi(13)
        val BRIGHT_CYAN = Ansi(14)
        val BRIGHT_WHITE = Ansi(15)
    }
}

/**
 * Text attributes for terminal cells.
 */
data class CellAttributes(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val blink: Boolean = false,
    val inverse: Boolean = false,
    val strikethrough: Boolean = false,
    val dim: Boolean = false,
    val hidden: Boolean = false
) {
    companion object {
        val DEFAULT = CellAttributes()
    }
}

/**
 * Style information for a terminal cell.
 */
data class CellStyle(
    val foreground: TerminalColor = TerminalColor.Default,
    val background: TerminalColor = TerminalColor.Default,
    val attributes: CellAttributes = CellAttributes.DEFAULT
) {
    companion object {
        val DEFAULT = CellStyle()
    }
}
