package com.terminox.presentation.terminal.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.animateColorAsState
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
import com.terminox.presentation.terminal.SpecialKey

/**
 * State for modifier keys with sticky behavior.
 */
data class ModifierState(
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val ctrlLocked: Boolean = false,
    val altLocked: Boolean = false,
    val shiftLocked: Boolean = false
)

/**
 * Enhanced extra keys toolbar with modifier toggles, sticky keys, and haptic feedback.
 */
@Composable
fun ExtraKeysToolbar(
    modifier: Modifier = Modifier,
    onKeyPress: (SpecialKey) -> Unit,
    onTextInput: (String) -> Unit,
    onCtrlKey: (Char) -> Unit = {}
) {
    val context = LocalContext.current
    var modifierState by remember { mutableStateOf(ModifierState()) }

    val vibrator = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    fun performHapticFeedback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(10)
        }
    }

    // Combine modifier with key and reset non-locked modifiers
    fun handleKeyWithModifiers(key: SpecialKey) {
        performHapticFeedback()
        onKeyPress(key)

        // Reset non-locked modifiers after key press
        modifierState = modifierState.copy(
            ctrl = modifierState.ctrlLocked,
            alt = modifierState.altLocked,
            shift = modifierState.shiftLocked
        )
    }

    fun handleCtrlChar(char: Char) {
        performHapticFeedback()
        onCtrlKey(char)
        modifierState = modifierState.copy(
            ctrl = modifierState.ctrlLocked,
            alt = modifierState.altLocked,
            shift = modifierState.shiftLocked
        )
    }

    Surface(
        color = Color(0xFF16213E),
        tonalElevation = 4.dp,
        modifier = modifier
    ) {
        Column {
            // Main toolbar row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // ESC
                ExtraKeyButton(
                    text = "ESC",
                    onClick = { handleKeyWithModifiers(SpecialKey.ESCAPE) },
                    onLongClick = { performHapticFeedback() }
                )

                // TAB
                ExtraKeyButton(
                    text = "TAB",
                    onClick = { handleKeyWithModifiers(SpecialKey.TAB) },
                    onLongClick = { performHapticFeedback() }
                )

                VerticalDivider(
                    modifier = Modifier.height(32.dp),
                    color = Color(0xFF0F3460)
                )

                // CTRL (toggle, long-press to lock)
                ModifierKeyButton(
                    text = "CTRL",
                    isActive = modifierState.ctrl || modifierState.ctrlLocked,
                    isLocked = modifierState.ctrlLocked,
                    onClick = {
                        performHapticFeedback()
                        modifierState = modifierState.copy(
                            ctrl = !modifierState.ctrl,
                            ctrlLocked = false
                        )
                    },
                    onLongClick = {
                        performHapticFeedback()
                        modifierState = modifierState.copy(
                            ctrlLocked = !modifierState.ctrlLocked,
                            ctrl = !modifierState.ctrlLocked
                        )
                    }
                )

                // ALT (toggle, long-press to lock)
                ModifierKeyButton(
                    text = "ALT",
                    isActive = modifierState.alt || modifierState.altLocked,
                    isLocked = modifierState.altLocked,
                    onClick = {
                        performHapticFeedback()
                        modifierState = modifierState.copy(
                            alt = !modifierState.alt,
                            altLocked = false
                        )
                    },
                    onLongClick = {
                        performHapticFeedback()
                        modifierState = modifierState.copy(
                            altLocked = !modifierState.altLocked,
                            alt = !modifierState.altLocked
                        )
                    }
                )

                // SHIFT (toggle, long-press to lock)
                ModifierKeyButton(
                    text = "SHIFT",
                    isActive = modifierState.shift || modifierState.shiftLocked,
                    isLocked = modifierState.shiftLocked,
                    onClick = {
                        performHapticFeedback()
                        modifierState = modifierState.copy(
                            shift = !modifierState.shift,
                            shiftLocked = false
                        )
                    },
                    onLongClick = {
                        performHapticFeedback()
                        modifierState = modifierState.copy(
                            shiftLocked = !modifierState.shiftLocked,
                            shift = !modifierState.shiftLocked
                        )
                    }
                )

                VerticalDivider(
                    modifier = Modifier.height(32.dp),
                    color = Color(0xFF0F3460)
                )

                // Arrow keys
                ExtraKeyButton(
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "Left",
                    onClick = { handleKeyWithModifiers(SpecialKey.ARROW_LEFT) },
                    onLongClick = { performHapticFeedback() }
                )
                ExtraKeyButton(
                    icon = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Down",
                    onClick = { handleKeyWithModifiers(SpecialKey.ARROW_DOWN) },
                    onLongClick = { performHapticFeedback() }
                )
                ExtraKeyButton(
                    icon = Icons.Default.KeyboardArrowUp,
                    contentDescription = "Up",
                    onClick = { handleKeyWithModifiers(SpecialKey.ARROW_UP) },
                    onLongClick = { performHapticFeedback() }
                )
                ExtraKeyButton(
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Right",
                    onClick = { handleKeyWithModifiers(SpecialKey.ARROW_RIGHT) },
                    onLongClick = { performHapticFeedback() }
                )

                VerticalDivider(
                    modifier = Modifier.height(32.dp),
                    color = Color(0xFF0F3460)
                )

                // Home/End
                ExtraKeyButton(
                    text = "HOME",
                    onClick = { handleKeyWithModifiers(SpecialKey.HOME) },
                    onLongClick = { performHapticFeedback() }
                )
                ExtraKeyButton(
                    text = "END",
                    onClick = { handleKeyWithModifiers(SpecialKey.END) },
                    onLongClick = { performHapticFeedback() }
                )

                // Page Up/Down
                ExtraKeyButton(
                    text = "PGUP",
                    onClick = { handleKeyWithModifiers(SpecialKey.PAGE_UP) },
                    onLongClick = { performHapticFeedback() }
                )
                ExtraKeyButton(
                    text = "PGDN",
                    onClick = { handleKeyWithModifiers(SpecialKey.PAGE_DOWN) },
                    onLongClick = { performHapticFeedback() }
                )

                VerticalDivider(
                    modifier = Modifier.height(32.dp),
                    color = Color(0xFF0F3460)
                )

                // Backspace/Delete
                ExtraKeyButton(
                    text = "BKSP",
                    onClick = { handleKeyWithModifiers(SpecialKey.BACKSPACE) },
                    onLongClick = { performHapticFeedback() }
                )
                ExtraKeyButton(
                    text = "DEL",
                    onClick = { handleKeyWithModifiers(SpecialKey.DELETE) },
                    onLongClick = { performHapticFeedback() }
                )
            }

            // Quick actions row (when CTRL is active)
            if (modifierState.ctrl || modifierState.ctrlLocked) {
                HorizontalDivider(color = Color(0xFF0F3460))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    CtrlKeyButton("C", "Cancel/Copy") { handleCtrlChar('c') }
                    CtrlKeyButton("D", "EOF") { handleCtrlChar('d') }
                    CtrlKeyButton("Z", "Suspend") { handleCtrlChar('z') }
                    CtrlKeyButton("L", "Clear") { handleCtrlChar('l') }
                    CtrlKeyButton("A", "Begin line") { handleCtrlChar('a') }
                    CtrlKeyButton("E", "End line") { handleCtrlChar('e') }
                    CtrlKeyButton("R", "Reverse search") { handleCtrlChar('r') }
                    CtrlKeyButton("W", "Delete word") { handleCtrlChar('w') }
                    CtrlKeyButton("U", "Clear line") { handleCtrlChar('u') }
                    CtrlKeyButton("K", "Kill line") { handleCtrlChar('k') }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExtraKeyButton(
    text: String? = null,
    icon: ImageVector? = null,
    contentDescription: String? = null,
    isActive: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isActive) Color(0xFF00FF00) else Color(0xFF0F3460),
        label = "backgroundColor"
    )
    val contentColor by animateColorAsState(
        targetValue = if (isActive) Color.Black else Color.White,
        label = "contentColor"
    )

    Surface(
        color = backgroundColor,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
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
                    tint = contentColor,
                    modifier = Modifier.size(20.dp)
                )
            } else if (text != null) {
                Text(
                    text = text,
                    color = contentColor,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModifierKeyButton(
    text: String,
    isActive: Boolean,
    isLocked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val backgroundColor by animateColorAsState(
        targetValue = when {
            isLocked -> Color(0xFFFFAA00) // Orange when locked
            isActive -> Color(0xFF00FF00) // Green when active
            else -> Color(0xFF0F3460)
        },
        label = "backgroundColor"
    )
    val contentColor by animateColorAsState(
        targetValue = if (isActive || isLocked) Color.Black else Color.White,
        label = "contentColor"
    )

    Surface(
        color = backgroundColor,
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
                color = contentColor,
                style = MaterialTheme.typography.labelMedium
            )
            if (isLocked) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = "Locked",
                    tint = contentColor,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

@Composable
private fun CtrlKeyButton(
    key: String,
    description: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color(0xFF1A4A7A),
        shape = MaterialTheme.shapes.small
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "^$key",
                color = Color(0xFF00FF00),
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                text = description,
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
