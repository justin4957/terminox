package com.terminox.presentation.sessionsharing

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.terminox.domain.model.SessionPermission
import com.terminox.domain.model.SessionViewer
import com.terminox.domain.model.SharingSettings

/**
 * Dialog for configuring session sharing settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharingSettingsDialog(
    currentSettings: SharingSettings,
    onDismiss: () -> Unit,
    onSave: (SharingSettings) -> Unit
) {
    var showCursors by remember { mutableStateOf(currentSettings.showCursors) }
    var showPresence by remember { mutableStateOf(currentSettings.showPresence) }
    var broadcastInputSource by remember { mutableStateOf(currentSettings.broadcastInputSource) }
    var idleTimeout by remember { mutableStateOf(currentSettings.idleTimeoutMinutes.toString()) }
    var allowControlRequests by remember { mutableStateOf(currentSettings.allowControlRequests) }
    var requireApproval by remember { mutableStateOf(currentSettings.requireApproval) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sharing Settings") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Configure how this session is shared with other viewers",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Show cursors toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Show cursor positions",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Display where other viewers are typing",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = showCursors,
                        onCheckedChange = { showCursors = it }
                    )
                }

                HorizontalDivider()

                // Show presence toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Show presence indicators",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Display viewer activity status",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = showPresence,
                        onCheckedChange = { showPresence = it }
                    )
                }

                HorizontalDivider()

                // Broadcast input source toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Broadcast input source",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Show who is typing in the terminal",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = broadcastInputSource,
                        onCheckedChange = { broadcastInputSource = it }
                    )
                }

                HorizontalDivider()

                // Idle timeout
                OutlinedTextField(
                    value = idleTimeout,
                    onValueChange = { if (it.all { char -> char.isDigit() }) idleTimeout = it },
                    label = { Text("Idle timeout (minutes)") },
                    modifier = Modifier.fillMaxWidth()
                )

                // Allow control requests toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Allow control requests",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Let viewers request write permission",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = allowControlRequests,
                        onCheckedChange = { allowControlRequests = it }
                    )
                }

                HorizontalDivider()

                // Require approval toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Require approval",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Manually approve new viewers",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = requireApproval,
                        onCheckedChange = { requireApproval = it }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val settings = SharingSettings(
                        showCursors = showCursors,
                        showPresence = showPresence,
                        broadcastInputSource = broadcastInputSource,
                        idleTimeoutMinutes = idleTimeout.toLongOrNull() ?: 5,
                        allowControlRequests = allowControlRequests,
                        requireApproval = requireApproval
                    )
                    onSave(settings)
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Dialog for changing a viewer's permission.
 */
@Composable
fun PermissionChangeDialog(
    viewer: SessionViewer,
    onDismiss: () -> Unit,
    onChange: (SessionPermission) -> Unit
) {
    var selectedPermission by remember { mutableStateOf(viewer.permission) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change Permission") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Change permission for ${viewer.displayName}",
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(8.dp))

                SessionPermission.entries.forEach { permission ->
                    PermissionOption(
                        permission = permission,
                        isSelected = selectedPermission == permission,
                        onClick = { selectedPermission = permission }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onChange(selectedPermission) },
                enabled = selectedPermission != viewer.permission
            ) {
                Text("Change")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Single permission option in the permission dialog.
 */
@Composable
fun PermissionOption(
    permission: SessionPermission,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = permission.displayName,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = when (permission) {
                        SessionPermission.VIEW_ONLY -> "Can view terminal output only"
                        SessionPermission.FULL_CONTROL -> "Can read and write to terminal"
                        SessionPermission.CONTROLLED -> "Can write with restrictions"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isSelected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
