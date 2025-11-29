package com.terminox.domain.model

import androidx.compose.ui.graphics.Color

/**
 * Terminal color theme with ANSI colors and UI colors.
 */
data class TerminalTheme(
    val name: String,
    val isDark: Boolean,
    // Terminal colors
    val background: Color,
    val foreground: Color,
    val cursor: Color,
    val cursorText: Color,
    val selection: Color,
    // ANSI standard colors (0-7)
    val black: Color,
    val red: Color,
    val green: Color,
    val yellow: Color,
    val blue: Color,
    val magenta: Color,
    val cyan: Color,
    val white: Color,
    // ANSI bright colors (8-15)
    val brightBlack: Color,
    val brightRed: Color,
    val brightGreen: Color,
    val brightYellow: Color,
    val brightBlue: Color,
    val brightMagenta: Color,
    val brightCyan: Color,
    val brightWhite: Color,
    // UI colors
    val toolbarBackground: Color,
    val toolbarForeground: Color,
    val tabBackground: Color,
    val tabActiveBackground: Color,
    val tabForeground: Color,
    val tabActiveForeground: Color,
    val divider: Color,
    val accent: Color
) {
    companion object {
        val DEFAULT = TerminalThemes.TERMINOX_DARK
    }

    /**
     * Get ANSI color by index (0-15).
     */
    fun getAnsiColor(index: Int): Color = when (index) {
        0 -> black
        1 -> red
        2 -> green
        3 -> yellow
        4 -> blue
        5 -> magenta
        6 -> cyan
        7 -> white
        8 -> brightBlack
        9 -> brightRed
        10 -> brightGreen
        11 -> brightYellow
        12 -> brightBlue
        13 -> brightMagenta
        14 -> brightCyan
        15 -> brightWhite
        else -> foreground
    }
}

/**
 * Collection of built-in terminal themes.
 */
object TerminalThemes {

    val TERMINOX_DARK = TerminalTheme(
        name = "Terminox Dark",
        isDark = true,
        background = Color(0xFF1A1A2E),
        foreground = Color(0xFFE4E4E4),
        cursor = Color(0xFF00FF00),
        cursorText = Color(0xFF000000),
        selection = Color(0x4D00FF00),
        black = Color(0xFF000000),
        red = Color(0xFFCD3131),
        green = Color(0xFF0DBC79),
        yellow = Color(0xFFE5E510),
        blue = Color(0xFF2472C8),
        magenta = Color(0xFFBC3FBC),
        cyan = Color(0xFF11A8CD),
        white = Color(0xFFE5E5E5),
        brightBlack = Color(0xFF666666),
        brightRed = Color(0xFFF14C4C),
        brightGreen = Color(0xFF23D18B),
        brightYellow = Color(0xFFF5F543),
        brightBlue = Color(0xFF3B8EEA),
        brightMagenta = Color(0xFFD670D6),
        brightCyan = Color(0xFF29B8DB),
        brightWhite = Color(0xFFFFFFFF),
        toolbarBackground = Color(0xFF16213E),
        toolbarForeground = Color(0xFFE4E4E4),
        tabBackground = Color(0xFF0F3460),
        tabActiveBackground = Color(0xFF1A4A7A),
        tabForeground = Color(0xFFAAAAAA),
        tabActiveForeground = Color(0xFFFFFFFF),
        divider = Color(0xFF0F3460),
        accent = Color(0xFF00FF00)
    )

    val SOLARIZED_DARK = TerminalTheme(
        name = "Solarized Dark",
        isDark = true,
        background = Color(0xFF002B36),
        foreground = Color(0xFF839496),
        cursor = Color(0xFF93A1A1),
        cursorText = Color(0xFF002B36),
        selection = Color(0x4D268BD2),
        black = Color(0xFF073642),
        red = Color(0xFFDC322F),
        green = Color(0xFF859900),
        yellow = Color(0xFFB58900),
        blue = Color(0xFF268BD2),
        magenta = Color(0xFFD33682),
        cyan = Color(0xFF2AA198),
        white = Color(0xFFEEE8D5),
        brightBlack = Color(0xFF002B36),
        brightRed = Color(0xFFCB4B16),
        brightGreen = Color(0xFF586E75),
        brightYellow = Color(0xFF657B83),
        brightBlue = Color(0xFF839496),
        brightMagenta = Color(0xFF6C71C4),
        brightCyan = Color(0xFF93A1A1),
        brightWhite = Color(0xFFFDF6E3),
        toolbarBackground = Color(0xFF073642),
        toolbarForeground = Color(0xFF839496),
        tabBackground = Color(0xFF073642),
        tabActiveBackground = Color(0xFF002B36),
        tabForeground = Color(0xFF657B83),
        tabActiveForeground = Color(0xFF93A1A1),
        divider = Color(0xFF073642),
        accent = Color(0xFF268BD2)
    )

    val SOLARIZED_LIGHT = TerminalTheme(
        name = "Solarized Light",
        isDark = false,
        background = Color(0xFFFDF6E3),
        foreground = Color(0xFF657B83),
        cursor = Color(0xFF586E75),
        cursorText = Color(0xFFFDF6E3),
        selection = Color(0x4D268BD2),
        black = Color(0xFF073642),
        red = Color(0xFFDC322F),
        green = Color(0xFF859900),
        yellow = Color(0xFFB58900),
        blue = Color(0xFF268BD2),
        magenta = Color(0xFFD33682),
        cyan = Color(0xFF2AA198),
        white = Color(0xFFEEE8D5),
        brightBlack = Color(0xFF002B36),
        brightRed = Color(0xFFCB4B16),
        brightGreen = Color(0xFF586E75),
        brightYellow = Color(0xFF657B83),
        brightBlue = Color(0xFF839496),
        brightMagenta = Color(0xFF6C71C4),
        brightCyan = Color(0xFF93A1A1),
        brightWhite = Color(0xFFFDF6E3),
        toolbarBackground = Color(0xFFEEE8D5),
        toolbarForeground = Color(0xFF657B83),
        tabBackground = Color(0xFFEEE8D5),
        tabActiveBackground = Color(0xFFFDF6E3),
        tabForeground = Color(0xFF93A1A1),
        tabActiveForeground = Color(0xFF586E75),
        divider = Color(0xFFEEE8D5),
        accent = Color(0xFF268BD2)
    )

    val DRACULA = TerminalTheme(
        name = "Dracula",
        isDark = true,
        background = Color(0xFF282A36),
        foreground = Color(0xFFF8F8F2),
        cursor = Color(0xFFF8F8F2),
        cursorText = Color(0xFF282A36),
        selection = Color(0x4D44475A),
        black = Color(0xFF21222C),
        red = Color(0xFFFF5555),
        green = Color(0xFF50FA7B),
        yellow = Color(0xFFF1FA8C),
        blue = Color(0xFFBD93F9),
        magenta = Color(0xFFFF79C6),
        cyan = Color(0xFF8BE9FD),
        white = Color(0xFFF8F8F2),
        brightBlack = Color(0xFF6272A4),
        brightRed = Color(0xFFFF6E6E),
        brightGreen = Color(0xFF69FF94),
        brightYellow = Color(0xFFFFFFA5),
        brightBlue = Color(0xFFD6ACFF),
        brightMagenta = Color(0xFFFF92DF),
        brightCyan = Color(0xFFA4FFFF),
        brightWhite = Color(0xFFFFFFFF),
        toolbarBackground = Color(0xFF21222C),
        toolbarForeground = Color(0xFFF8F8F2),
        tabBackground = Color(0xFF21222C),
        tabActiveBackground = Color(0xFF44475A),
        tabForeground = Color(0xFF6272A4),
        tabActiveForeground = Color(0xFFF8F8F2),
        divider = Color(0xFF44475A),
        accent = Color(0xFFBD93F9)
    )

    val NORD = TerminalTheme(
        name = "Nord",
        isDark = true,
        background = Color(0xFF2E3440),
        foreground = Color(0xFFD8DEE9),
        cursor = Color(0xFFD8DEE9),
        cursorText = Color(0xFF2E3440),
        selection = Color(0x4D88C0D0),
        black = Color(0xFF3B4252),
        red = Color(0xFFBF616A),
        green = Color(0xFFA3BE8C),
        yellow = Color(0xFFEBCB8B),
        blue = Color(0xFF81A1C1),
        magenta = Color(0xFFB48EAD),
        cyan = Color(0xFF88C0D0),
        white = Color(0xFFE5E9F0),
        brightBlack = Color(0xFF4C566A),
        brightRed = Color(0xFFBF616A),
        brightGreen = Color(0xFFA3BE8C),
        brightYellow = Color(0xFFEBCB8B),
        brightBlue = Color(0xFF81A1C1),
        brightMagenta = Color(0xFFB48EAD),
        brightCyan = Color(0xFF8FBCBB),
        brightWhite = Color(0xFFECEFF4),
        toolbarBackground = Color(0xFF3B4252),
        toolbarForeground = Color(0xFFD8DEE9),
        tabBackground = Color(0xFF3B4252),
        tabActiveBackground = Color(0xFF434C5E),
        tabForeground = Color(0xFF4C566A),
        tabActiveForeground = Color(0xFFECEFF4),
        divider = Color(0xFF4C566A),
        accent = Color(0xFF88C0D0)
    )

    val MONOKAI = TerminalTheme(
        name = "Monokai",
        isDark = true,
        background = Color(0xFF272822),
        foreground = Color(0xFFF8F8F2),
        cursor = Color(0xFFF8F8F0),
        cursorText = Color(0xFF272822),
        selection = Color(0x4D49483E),
        black = Color(0xFF272822),
        red = Color(0xFFF92672),
        green = Color(0xFFA6E22E),
        yellow = Color(0xFFF4BF75),
        blue = Color(0xFF66D9EF),
        magenta = Color(0xFFAE81FF),
        cyan = Color(0xFFA1EFE4),
        white = Color(0xFFF8F8F2),
        brightBlack = Color(0xFF75715E),
        brightRed = Color(0xFFF92672),
        brightGreen = Color(0xFFA6E22E),
        brightYellow = Color(0xFFF4BF75),
        brightBlue = Color(0xFF66D9EF),
        brightMagenta = Color(0xFFAE81FF),
        brightCyan = Color(0xFFA1EFE4),
        brightWhite = Color(0xFFF9F8F5),
        toolbarBackground = Color(0xFF1E1F1C),
        toolbarForeground = Color(0xFFF8F8F2),
        tabBackground = Color(0xFF1E1F1C),
        tabActiveBackground = Color(0xFF49483E),
        tabForeground = Color(0xFF75715E),
        tabActiveForeground = Color(0xFFF8F8F2),
        divider = Color(0xFF49483E),
        accent = Color(0xFFA6E22E)
    )

    val GRUVBOX_DARK = TerminalTheme(
        name = "Gruvbox Dark",
        isDark = true,
        background = Color(0xFF282828),
        foreground = Color(0xFFEBDBB2),
        cursor = Color(0xFFEBDBB2),
        cursorText = Color(0xFF282828),
        selection = Color(0x4D504945),
        black = Color(0xFF282828),
        red = Color(0xFFCC241D),
        green = Color(0xFF98971A),
        yellow = Color(0xFFD79921),
        blue = Color(0xFF458588),
        magenta = Color(0xFFB16286),
        cyan = Color(0xFF689D6A),
        white = Color(0xFFA89984),
        brightBlack = Color(0xFF928374),
        brightRed = Color(0xFFFB4934),
        brightGreen = Color(0xFFB8BB26),
        brightYellow = Color(0xFFFABD2F),
        brightBlue = Color(0xFF83A598),
        brightMagenta = Color(0xFFD3869B),
        brightCyan = Color(0xFF8EC07C),
        brightWhite = Color(0xFFEBDBB2),
        toolbarBackground = Color(0xFF1D2021),
        toolbarForeground = Color(0xFFEBDBB2),
        tabBackground = Color(0xFF1D2021),
        tabActiveBackground = Color(0xFF3C3836),
        tabForeground = Color(0xFF928374),
        tabActiveForeground = Color(0xFFEBDBB2),
        divider = Color(0xFF504945),
        accent = Color(0xFFFABD2F)
    )

    val ONE_DARK = TerminalTheme(
        name = "One Dark",
        isDark = true,
        background = Color(0xFF282C34),
        foreground = Color(0xFFABB2BF),
        cursor = Color(0xFF528BFF),
        cursorText = Color(0xFF282C34),
        selection = Color(0x403E4451),
        black = Color(0xFF282C34),
        red = Color(0xFFE06C75),
        green = Color(0xFF98C379),
        yellow = Color(0xFFE5C07B),
        blue = Color(0xFF61AFEF),
        magenta = Color(0xFFC678DD),
        cyan = Color(0xFF56B6C2),
        white = Color(0xFFABB2BF),
        brightBlack = Color(0xFF5C6370),
        brightRed = Color(0xFFE06C75),
        brightGreen = Color(0xFF98C379),
        brightYellow = Color(0xFFE5C07B),
        brightBlue = Color(0xFF61AFEF),
        brightMagenta = Color(0xFFC678DD),
        brightCyan = Color(0xFF56B6C2),
        brightWhite = Color(0xFFFFFFFF),
        toolbarBackground = Color(0xFF21252B),
        toolbarForeground = Color(0xFFABB2BF),
        tabBackground = Color(0xFF21252B),
        tabActiveBackground = Color(0xFF2C313A),
        tabForeground = Color(0xFF5C6370),
        tabActiveForeground = Color(0xFFABB2BF),
        divider = Color(0xFF3E4451),
        accent = Color(0xFF61AFEF)
    )

    /**
     * All available themes.
     */
    val ALL_THEMES = listOf(
        TERMINOX_DARK,
        SOLARIZED_DARK,
        SOLARIZED_LIGHT,
        DRACULA,
        NORD,
        MONOKAI,
        GRUVBOX_DARK,
        ONE_DARK
    )

    /**
     * Find theme by name.
     */
    fun findByName(name: String): TerminalTheme? = ALL_THEMES.find { it.name == name }
}
