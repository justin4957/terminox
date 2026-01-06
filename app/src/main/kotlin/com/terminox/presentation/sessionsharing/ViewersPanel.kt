package com.terminox.presentation.sessionsharing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.terminox.domain.model.SessionPermission
import com.terminox.domain.model.SessionViewer
import com.terminox.domain.model.SharedSession

/**
 * Panel showing connected viewers for a shared session.
 */
@Composable
fun ViewersPanel(
    session: SharedSession,
    currentViewerId: String,
    onPermissionChange: (SessionViewer) -> Unit,
    onKickViewer: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val isOwner = session.isOwner(currentViewerId)

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Connected Viewers (${session.getActiveViewerCount()}/${session.maxViewers})",
                    style = MaterialTheme.typography.titleMedium
                )

                if (session.hasReachedViewerLimit()) {
                    Text(
                        text = "FULL",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(session.viewers, key = { it.id }) { viewer ->
                    ViewerCard(
                        viewer = viewer,
                        isOwner = session.isOwner(viewer.id),
                        isCurrentUser = viewer.id == currentViewerId,
                        canManage = isOwner && viewer.id != currentViewerId,
                        onPermissionChange = { onPermissionChange(viewer) },
                        onKick = { onKickViewer(viewer.id) }
                    )
                }
            }

            if (isOwner && session.getActiveViewerCount() < session.maxViewers) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${session.maxViewers - session.getActiveViewerCount()} slots available",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Card representing a single viewer.
 */
@Composable
fun ViewerCard(
    viewer: SessionViewer,
    isOwner: Boolean,
    isCurrentUser: Boolean,
    canManage: Boolean,
    onPermissionChange: () -> Unit,
    onKick: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrentUser) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Viewer color indicator
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(
                            parseColor(viewer.color) ?: MaterialTheme.colorScheme.primary
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = viewer.displayName.take(1).uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White
                    )
                }

                Column {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = viewer.displayName,
                            style = MaterialTheme.typography.bodyMedium
                        )

                        if (isOwner) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = "Owner",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        if (isCurrentUser) {
                            Text(
                                text = "(You)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PermissionBadge(permission = viewer.permission)

                        Icon(
                            imageVector = when (viewer.deviceType) {
                                com.terminox.domain.model.ViewerDeviceType.MOBILE -> Icons.Default.Phone
                                com.terminox.domain.model.ViewerDeviceType.TABLET -> Icons.Default.Tablet
                                com.terminox.domain.model.ViewerDeviceType.DESKTOP -> Icons.Default.Computer
                                com.terminox.domain.model.ViewerDeviceType.WEB -> Icons.Default.Web
                            },
                            contentDescription = viewer.deviceType.displayName,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Text(
                            text = viewer.getTimeSinceActivity(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Status indicator
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(
                        if (viewer.isActive) {
                            if (viewer.isIdle()) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                Color(0xFF4CAF50) // Green
                            }
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
            )

            if (canManage) {
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "More options"
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Change Permission") },
                            onClick = {
                                showMenu = false
                                onPermissionChange()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Security, contentDescription = null)
                            }
                        )

                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Kick Viewer",
                                    color = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                showMenu = false
                                onKick()
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.PersonRemove,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Badge showing viewer permission level.
 */
@Composable
fun PermissionBadge(permission: SessionPermission) {
    val (icon, color) = when (permission) {
        SessionPermission.VIEW_ONLY -> Icons.Default.Visibility to MaterialTheme.colorScheme.secondary
        SessionPermission.FULL_CONTROL -> Icons.Default.Edit to MaterialTheme.colorScheme.primary
        SessionPermission.CONTROLLED -> Icons.Default.EditNote to MaterialTheme.colorScheme.tertiary
    }

    Surface(
        color = color.copy(alpha = 0.1f),
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = color
            )
            Text(
                text = permission.displayName,
                style = MaterialTheme.typography.labelSmall,
                color = color
            )
        }
    }
}

/**
 * Parse hex color string to Color.
 */
private fun parseColor(hex: String?): Color? {
    if (hex == null || !hex.startsWith("#")) return null
    return try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (e: IllegalArgumentException) {
        null
    }
}
