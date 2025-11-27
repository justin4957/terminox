package com.terminox.presentation.terminal.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Clipboard actions available in the terminal.
 */
enum class ClipboardAction {
    COPY,
    PASTE,
    SELECT_ALL
}

/**
 * Clipboard helper for terminal text operations.
 */
class TerminalClipboardHelper(private val context: Context) {

    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /**
     * Copies text to the system clipboard.
     */
    fun copyToClipboard(text: String, label: String = "Terminal Text") {
        val clip = ClipData.newPlainText(label, text)
        clipboardManager.setPrimaryClip(clip)
    }

    /**
     * Gets text from the system clipboard.
     */
    fun getFromClipboard(): String? {
        return if (clipboardManager.hasPrimaryClip()) {
            clipboardManager.primaryClip?.getItemAt(0)?.text?.toString()
        } else {
            null
        }
    }

    /**
     * Checks if clipboard has text content.
     */
    fun hasClipboardContent(): Boolean {
        return clipboardManager.hasPrimaryClip() &&
                clipboardManager.primaryClipDescription?.hasMimeType("text/plain") == true
    }
}

/**
 * Floating action bar for clipboard operations.
 * Shows when text is selected in the terminal.
 */
@Composable
fun ClipboardActionBar(
    visible: Boolean,
    hasSelection: Boolean,
    onAction: (ClipboardAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardHelper = remember { TerminalClipboardHelper(context) }
    val hasPasteContent = remember { clipboardHelper.hasClipboardContent() }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Surface(
            color = Color(0xFF0F3460),
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 8.dp,
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier.padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Copy button
                IconButton(
                    onClick = { onAction(ClipboardAction.COPY) },
                    enabled = hasSelection
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        tint = if (hasSelection) Color(0xFF00FF00) else Color.Gray
                    )
                }

                // Paste button
                IconButton(
                    onClick = { onAction(ClipboardAction.PASTE) },
                    enabled = hasPasteContent
                ) {
                    Icon(
                        Icons.Default.ContentPaste,
                        contentDescription = "Paste",
                        tint = if (hasPasteContent) Color(0xFF00FF00) else Color.Gray
                    )
                }

                // Select all button
                IconButton(onClick = { onAction(ClipboardAction.SELECT_ALL) }) {
                    Icon(
                        Icons.Default.SelectAll,
                        contentDescription = "Select All",
                        tint = Color(0xFF00FF00)
                    )
                }
            }
        }
    }
}

/**
 * Selection highlight overlay for the terminal.
 */
@Composable
fun SelectionOverlay(
    selection: TextSelection?,
    cellWidth: Float,
    cellHeight: Float,
    modifier: Modifier = Modifier
) {
    if (selection == null || selection.isEmpty()) return

    val normalized = selection.normalize()

    BoxWithConstraints(modifier = modifier) {
        // Calculate selection bounds
        if (normalized.startRow == normalized.endRow) {
            // Single line selection
            val x = normalized.startColumn * cellWidth
            val y = normalized.startRow * cellHeight
            val width = (normalized.endColumn - normalized.startColumn) * cellWidth

            Box(
                modifier = Modifier
                    .offset(x = x.dp, y = y.dp)
                    .size(width = width.dp, height = cellHeight.dp)
                    .background(Color(0xFF00FF00).copy(alpha = 0.3f))
            )
        } else {
            // Multi-line selection
            // First line: from startColumn to end
            // Middle lines: full width
            // Last line: from start to endColumn

            for (row in normalized.startRow..normalized.endRow) {
                val (startCol, endCol) = when (row) {
                    normalized.startRow -> normalized.startColumn to Int.MAX_VALUE
                    normalized.endRow -> 0 to normalized.endColumn
                    else -> 0 to Int.MAX_VALUE
                }

                val x = startCol * cellWidth
                val y = row * cellHeight
                val maxWidth = maxWidth.value
                val endX = if (endCol == Int.MAX_VALUE) maxWidth else endCol * cellWidth
                val width = (endX - x).coerceAtLeast(0f)

                Box(
                    modifier = Modifier
                        .offset(x = x.dp, y = y.dp)
                        .size(width = width.dp, height = cellHeight.dp)
                        .background(Color(0xFF00FF00).copy(alpha = 0.3f))
                )
            }
        }
    }
}

/**
 * Snackbar message types for clipboard operations.
 */
sealed class ClipboardMessage {
    data object CopiedToClipboard : ClipboardMessage()
    data object PastedFromClipboard : ClipboardMessage()
    data object ClipboardEmpty : ClipboardMessage()
    data object SelectionCleared : ClipboardMessage()
}

/**
 * Returns the display message for clipboard operations.
 */
fun ClipboardMessage.toDisplayString(): String {
    return when (this) {
        is ClipboardMessage.CopiedToClipboard -> "Copied to clipboard"
        is ClipboardMessage.PastedFromClipboard -> "Pasted from clipboard"
        is ClipboardMessage.ClipboardEmpty -> "Clipboard is empty"
        is ClipboardMessage.SelectionCleared -> "Selection cleared"
    }
}
