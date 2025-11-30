package com.terminox.domain.model

/**
 * Terminal display and behavior settings.
 */
data class TerminalSettings(
    val themeName: String = "Terminox Dark",
    val fontSize: Float = 14f,
    val fontFamily: String = "monospace",
    val showTabBar: Boolean = true,
    val immersiveMode: Boolean = false,
    val hapticFeedback: Boolean = true,
    val bellSound: Boolean = false,
    val bellVibrate: Boolean = true,
    val keepScreenOn: Boolean = true,
    val scrollbackLines: Int = 10000,
    val cursorStyle: CursorStyle = CursorStyle.BLOCK,
    val cursorBlink: Boolean = true,
    val keyboardLayout: KeyboardLayout = KeyboardLayout.STANDARD,
    val swipeGestures: Boolean = true,
    val doubleTapToSelect: Boolean = true,
    val autoCorrect: Boolean = false
) {
    val theme: TerminalTheme
        get() = TerminalThemes.findByName(themeName) ?: TerminalTheme.DEFAULT

    companion object {
        val DEFAULT = TerminalSettings()

        const val MIN_FONT_SIZE = 8f
        const val MAX_FONT_SIZE = 32f
        const val FONT_SIZE_STEP = 1f
    }
}

/**
 * Cursor display styles.
 */
enum class CursorStyle {
    BLOCK,
    UNDERLINE,
    BAR
}

/**
 * Keyboard layout configurations.
 */
enum class KeyboardLayout {
    STANDARD,
    COMPACT,
    EXTENDED,
    VIM_OPTIMIZED
}

/**
 * Tab information within a session.
 */
data class SessionTab(
    val tabId: String,
    val sessionId: String,
    val channelId: String,
    val title: String = "Shell",
    val workingDirectory: String? = null,
    val isActive: Boolean = false,
    val hasUnreadOutput: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
