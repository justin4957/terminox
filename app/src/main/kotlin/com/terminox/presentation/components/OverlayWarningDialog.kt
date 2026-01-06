package com.terminox.presentation.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Warning dialog shown when an overlay is detected during security-sensitive operations.
 * Part of AV-13: Overlay Attack Protection.
 */
@Composable
fun OverlayWarningDialog(
    onDismiss: () -> Unit,
    onContinueAnyway: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Warning,
                contentDescription = "Security Warning",
                tint = Color(0xFFFF6B6B)
            )
        },
        title = {
            Text("Security Warning: Overlay Detected")
        },
        text = {
            Text(
                "Another application is drawing over this screen. " +
                        "This could be a security risk.\n\n" +
                        "To ensure your safety:\n" +
                        "1. Close any overlay apps (screen filters, chat heads, etc.)\n" +
                        "2. Verify no malicious apps are installed\n" +
                        "3. Try again\n\n" +
                        "Do not enter sensitive information while overlays are active."
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Go Back")
            }
        },
        dismissButton = onContinueAnyway?.let {
            {
                TextButton(onClick = it) {
                    Text("Continue Anyway (Not Recommended)")
                }
            }
        }
    )
}
