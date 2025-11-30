package com.terminox.presentation.terminal.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.terminox.domain.model.KeyboardLayout
import com.terminox.domain.model.TerminalTheme
import com.terminox.presentation.terminal.SpecialKey

/**
 * Enhanced keyboard toolbar with multiple rows, function keys, and theme support.
 */
@Composable
fun EnhancedKeyboardBar(
    theme: TerminalTheme,
    layout: KeyboardLayout = KeyboardLayout.STANDARD,
    hapticEnabled: Boolean = true,
    onKeyPress: (SpecialKey) -> Unit,
    onTextInput: (String) -> Unit,
    onCtrlKey: (Char) -> Unit,
    onFunctionKey: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var modifierState by remember { mutableStateOf(KeyModifierState()) }
    var showFunctionKeys by remember { mutableStateOf(false) }

    val vibrator = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    fun performHaptic() {
        if (!hapticEnabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(10)
        }
    }

    fun handleKey(key: SpecialKey) {
        performHaptic()
        onKeyPress(key)
        resetNonLockedModifiers(modifierState) { modifierState = it }
    }

    fun handleCtrl(char: Char) {
        performHaptic()
        onCtrlKey(char)
        resetNonLockedModifiers(modifierState) { modifierState = it }
    }

    fun handleFn(num: Int) {
        performHaptic()
        onFunctionKey(num)
        resetNonLockedModifiers(modifierState) { modifierState = it }
    }

    Surface(
        color = theme.toolbarBackground,
        tonalElevation = 4.dp,
        modifier = modifier
    ) {
        Column {
            // Function keys row (expandable)
            AnimatedVisibility(
                visible = showFunctionKeys,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                FunctionKeysRow(
                    theme = theme,
                    onFunctionKey = { handleFn(it) }
                )
            }

            // Main keys row
            MainKeysRow(
                theme = theme,
                modifierState = modifierState,
                showFnKeys = showFunctionKeys,
                onToggleFnKeys = { showFunctionKeys = !showFunctionKeys },
                onModifierToggle = { type, locked ->
                    modifierState = toggleModifier(modifierState, type, locked)
                    performHaptic()
                },
                onKeyPress = { handleKey(it) }
            )

            // Ctrl shortcuts row (when CTRL active)
            AnimatedVisibility(
                visible = modifierState.ctrl || modifierState.ctrlLocked,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                CtrlShortcutsRow(
                    theme = theme,
                    onCtrlKey = { handleCtrl(it) }
                )
            }
        }
    }
}

@Composable
private fun FunctionKeysRow(
    theme: TerminalTheme,
    onFunctionKey: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        for (i in 1..12) {
            KeyButton(
                text = "F$i",
                theme = theme,
                onClick = { onFunctionKey(i) }
            )
        }
    }
    HorizontalDivider(color = theme.divider)
}

@Composable
private fun MainKeysRow(
    theme: TerminalTheme,
    modifierState: KeyModifierState,
    showFnKeys: Boolean,
    onToggleFnKeys: () -> Unit,
    onModifierToggle: (ModifierType, Boolean) -> Unit,
    onKeyPress: (SpecialKey) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Function keys toggle
        KeyButton(
            text = "Fn",
            theme = theme,
            isActive = showFnKeys,
            onClick = onToggleFnKeys
        )

        // ESC
        KeyButton(text = "ESC", theme = theme, onClick = { onKeyPress(SpecialKey.ESCAPE) })

        // TAB
        KeyButton(text = "TAB", theme = theme, onClick = { onKeyPress(SpecialKey.TAB) })

        VerticalDivider(modifier = Modifier.height(32.dp), color = theme.divider)

        // Modifier keys
        ModifierKey(
            text = "CTRL",
            theme = theme,
            isActive = modifierState.ctrl || modifierState.ctrlLocked,
            isLocked = modifierState.ctrlLocked,
            onClick = { onModifierToggle(ModifierType.CTRL, false) },
            onLongClick = { onModifierToggle(ModifierType.CTRL, true) }
        )

        ModifierKey(
            text = "ALT",
            theme = theme,
            isActive = modifierState.alt || modifierState.altLocked,
            isLocked = modifierState.altLocked,
            onClick = { onModifierToggle(ModifierType.ALT, false) },
            onLongClick = { onModifierToggle(ModifierType.ALT, true) }
        )

        ModifierKey(
            text = "SHIFT",
            theme = theme,
            isActive = modifierState.shift || modifierState.shiftLocked,
            isLocked = modifierState.shiftLocked,
            onClick = { onModifierToggle(ModifierType.SHIFT, false) },
            onLongClick = { onModifierToggle(ModifierType.SHIFT, true) }
        )

        VerticalDivider(modifier = Modifier.height(32.dp), color = theme.divider)

        // Arrow keys
        KeyButton(
            icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            contentDescription = "Left",
            theme = theme,
            onClick = { onKeyPress(SpecialKey.ARROW_LEFT) }
        )
        KeyButton(
            icon = Icons.Default.KeyboardArrowDown,
            contentDescription = "Down",
            theme = theme,
            onClick = { onKeyPress(SpecialKey.ARROW_DOWN) }
        )
        KeyButton(
            icon = Icons.Default.KeyboardArrowUp,
            contentDescription = "Up",
            theme = theme,
            onClick = { onKeyPress(SpecialKey.ARROW_UP) }
        )
        KeyButton(
            icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "Right",
            theme = theme,
            onClick = { onKeyPress(SpecialKey.ARROW_RIGHT) }
        )

        VerticalDivider(modifier = Modifier.height(32.dp), color = theme.divider)

        // Navigation keys
        KeyButton(text = "HOME", theme = theme, onClick = { onKeyPress(SpecialKey.HOME) })
        KeyButton(text = "END", theme = theme, onClick = { onKeyPress(SpecialKey.END) })
        KeyButton(text = "PGUP", theme = theme, onClick = { onKeyPress(SpecialKey.PAGE_UP) })
        KeyButton(text = "PGDN", theme = theme, onClick = { onKeyPress(SpecialKey.PAGE_DOWN) })
        KeyButton(text = "DEL", theme = theme, onClick = { onKeyPress(SpecialKey.DELETE) })
    }
}

@Composable
private fun CtrlShortcutsRow(
    theme: TerminalTheme,
    onCtrlKey: (Char) -> Unit
) {
    HorizontalDivider(color = theme.divider)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        CtrlShortcut("C", "Cancel", theme, onCtrlKey)
        CtrlShortcut("D", "EOF", theme, onCtrlKey)
        CtrlShortcut("Z", "Suspend", theme, onCtrlKey)
        CtrlShortcut("L", "Clear", theme, onCtrlKey)
        CtrlShortcut("A", "Start", theme, onCtrlKey)
        CtrlShortcut("E", "End", theme, onCtrlKey)
        CtrlShortcut("R", "Search", theme, onCtrlKey)
        CtrlShortcut("W", "DelWord", theme, onCtrlKey)
        CtrlShortcut("U", "DelLine", theme, onCtrlKey)
        CtrlShortcut("K", "Kill", theme, onCtrlKey)
        CtrlShortcut("P", "Prev", theme, onCtrlKey)
        CtrlShortcut("N", "Next", theme, onCtrlKey)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeyButton(
    text: String? = null,
    icon: ImageVector? = null,
    contentDescription: String? = null,
    theme: TerminalTheme,
    isActive: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val bgColor by animateColorAsState(
        targetValue = if (isActive) theme.accent else theme.tabBackground,
        label = "keyBg"
    )
    val textColor by animateColorAsState(
        targetValue = if (isActive) Color.Black else theme.tabForeground,
        label = "keyText"
    )

    Surface(
        color = bgColor,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick ?: {}
        )
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = textColor,
                    modifier = Modifier.size(20.dp)
                )
            } else if (text != null) {
                Text(
                    text = text,
                    color = textColor,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModifierKey(
    text: String,
    theme: TerminalTheme,
    isActive: Boolean,
    isLocked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = when {
            isLocked -> Color(0xFFFFAA00)
            isActive -> theme.accent
            else -> theme.tabBackground
        },
        label = "modBg"
    )
    val textColor by animateColorAsState(
        targetValue = if (isActive || isLocked) Color.Black else theme.tabForeground,
        label = "modText"
    )

    Surface(
        color = bgColor,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = text,
                color = textColor,
                style = MaterialTheme.typography.labelMedium
            )
            if (isLocked) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = "Locked",
                    tint = textColor,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

@Composable
private fun CtrlShortcut(
    key: String,
    label: String,
    theme: TerminalTheme,
    onClick: (Char) -> Unit
) {
    Surface(
        onClick = { onClick(key.lowercase()[0]) },
        color = theme.tabActiveBackground,
        shape = MaterialTheme.shapes.small
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "^$key",
                color = theme.accent,
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                text = label,
                color = theme.tabForeground.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

// Modifier state management
data class KeyModifierState(
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val ctrlLocked: Boolean = false,
    val altLocked: Boolean = false,
    val shiftLocked: Boolean = false
)

enum class ModifierType { CTRL, ALT, SHIFT }

private fun toggleModifier(state: KeyModifierState, type: ModifierType, lock: Boolean): KeyModifierState {
    return when (type) {
        ModifierType.CTRL -> if (lock) {
            state.copy(ctrlLocked = !state.ctrlLocked, ctrl = !state.ctrlLocked)
        } else {
            state.copy(ctrl = !state.ctrl, ctrlLocked = false)
        }
        ModifierType.ALT -> if (lock) {
            state.copy(altLocked = !state.altLocked, alt = !state.altLocked)
        } else {
            state.copy(alt = !state.alt, altLocked = false)
        }
        ModifierType.SHIFT -> if (lock) {
            state.copy(shiftLocked = !state.shiftLocked, shift = !state.shiftLocked)
        } else {
            state.copy(shift = !state.shift, shiftLocked = false)
        }
    }
}

private fun resetNonLockedModifiers(state: KeyModifierState, update: (KeyModifierState) -> Unit) {
    update(state.copy(
        ctrl = state.ctrlLocked,
        alt = state.altLocked,
        shift = state.shiftLocked
    ))
}
