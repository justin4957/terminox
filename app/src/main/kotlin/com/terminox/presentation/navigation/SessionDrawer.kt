package com.terminox.presentation.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.terminox.domain.model.SessionState
import com.terminox.domain.model.UnifiedSessionInfo
import com.terminox.domain.model.SessionSource
import com.terminox.domain.model.SessionIconType
import com.terminox.domain.model.SessionGrouping
import com.terminox.domain.model.SessionFilter

@RequiresOptIn(message = "This is an experimental Layout API")
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class ExperimentalLayoutApi

/**
 * Represents a terminal session in the drawer (legacy - use UnifiedSessionInfo).
 * Kept for backwards compatibility.
 */
@Deprecated("Use UnifiedSessionInfo instead", ReplaceWith("UnifiedSessionInfo"))
data class SessionInfo(
    val sessionId: String,
    val connectionId: String,
    val connectionName: String,
    val host: String,
    val username: String,
    val state: SessionState,
    val isActive: Boolean = false
)

/**
 * Session drawer for managing multiple terminal sessions from all sources (SSH, Agent, EC2, Local).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDrawer(
    sessions: List<UnifiedSessionInfo>,
    onSessionClick: (UnifiedSessionInfo) -> Unit,
    onSessionClose: (UnifiedSessionInfo) -> Unit,
    onSessionPin: (UnifiedSessionInfo) -> Unit,
    onSessionReorder: (UnifiedSessionInfo, Int) -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier,
    groupBySource: Boolean = true,
    enableDragDrop: Boolean = true
) {
    var searchQuery by remember { mutableStateOf("") }
    var showFilterDialog by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(SessionFilter()) }

    val filteredSessions = remember(sessions, searchQuery, filter) {
        val updatedFilter = filter.copy(searchQuery = searchQuery)
        sessions.filter { updatedFilter.matches(it) }
            .sortedWith(
                compareByDescending<UnifiedSessionInfo> { it.isPinned }
                    .thenBy { it.displayOrder }
                    .thenByDescending { it.startedAt }
            )
    }

    val sessionGroups = remember(filteredSessions, groupBySource) {
        if (groupBySource) {
            filteredSessions.groupBy { it.getGroupKey() }
                .map { (groupName, groupSessions) ->
                    SessionGrouping(name = groupName, sessions = groupSessions)
                }
                .sortedWith(
                    compareByDescending<SessionGrouping> { group ->
                        group.sessions.any { it.isPinned }
                    }.thenBy { it.name }
                )
        } else {
            listOf(SessionGrouping(name = "All Sessions", sessions = filteredSessions))
        }
    }
    ModalDrawerSheet(
        modifier = modifier.width(300.dp),
        drawerContainerColor = Color(0xFF16213E)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Header
            Surface(
                color = Color(0xFF0F3460),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Sessions",
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White
                            )
                            Text(
                                text = "${filteredSessions.size} of ${sessions.size} sessions",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            FilledTonalIconButton(
                                onClick = { showFilterDialog = true },
                                modifier = Modifier.size(36.dp),
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = if (filter.sources.isNotEmpty() || filter.states.isNotEmpty() || filter.onlyPinned) {
                                        Color(0xFF00FF00)
                                    } else {
                                        Color(0xFF1A4A7A)
                                    },
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(
                                    Icons.Default.FilterList,
                                    contentDescription = "Filter sessions",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            FilledTonalIconButton(
                                onClick = onNewSession,
                                modifier = Modifier.size(36.dp),
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = Color(0xFF00FF00),
                                    contentColor = Color.Black
                                )
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = "New Session",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    // Search bar
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = {
                            Text(
                                "Search sessions...",
                                color = Color.White.copy(alpha = 0.5f),
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = { searchQuery = "" },
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Clear,
                                        contentDescription = "Clear search",
                                        tint = Color.White.copy(alpha = 0.7f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00FF00),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.3f)
                        ),
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Sessions list
            if (sessions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Terminal,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = Color.White.copy(alpha = 0.5f)
                        )
                        Text(
                            text = "No active sessions",
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(onClick = onNewSession) {
                            Text("Start a new session")
                        }
                    }
                }
            } else if (filteredSessions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.SearchOff,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = Color.White.copy(alpha = 0.5f)
                        )
                        Text(
                            text = "No sessions match your filter",
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(onClick = { searchQuery = ""; filter = SessionFilter() }) {
                            Text("Clear filters")
                        }
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    sessionGroups.forEach { group ->
                        item(key = "group_${group.name}") {
                            SessionGroupHeader(groupName = group.name, sessionCount = group.sessions.size)
                        }

                        itemsIndexed(
                            items = group.sessions,
                            key = { _, session -> session.sessionId }
                        ) { index, session ->
                            UnifiedSessionItem(
                                session = session,
                                onClick = { onSessionClick(session) },
                                onClose = { onSessionClose(session) },
                                onPin = { onSessionPin(session) },
                                enableDragDrop = enableDragDrop,
                                onReorder = { newOrder -> onSessionReorder(session, newOrder) }
                            )
                        }
                    }
                }
            }
        }

        // Filter dialog
        if (showFilterDialog) {
            SessionFilterDialog(
                filter = filter,
                onFilterChange = { filter = it },
                onDismiss = { showFilterDialog = false }
            )
        }
    }
}

/**
 * Session group header for visually separating session groups.
 */
@Composable
private fun SessionGroupHeader(
    groupName: String,
    sessionCount: Int
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = groupName,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.6f),
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "$sessionCount",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.5f)
        )
    }
}

/**
 * Get icon for session source type.
 */
private fun getSessionIcon(iconType: SessionIconType): ImageVector = when (iconType) {
    SessionIconType.SSH -> Icons.Default.Computer
    SessionIconType.AGENT -> Icons.Default.DevicesOther
    SessionIconType.CLOUD -> Icons.Default.Cloud
    SessionIconType.LOCAL -> Icons.Default.PhoneAndroid
}

/**
 * Unified session item supporting all session sources.
 */
@Composable
private fun UnifiedSessionItem(
    session: UnifiedSessionInfo,
    onClick: () -> Unit,
    onClose: () -> Unit,
    onPin: () -> Unit,
    enableDragDrop: Boolean,
    onReorder: (Int) -> Unit
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (session.isActive) Color(0xFF1A4A7A) else Color(0xFF0F3460),
        label = "backgroundColor"
    )

    val stateColor = when (session.state) {
        SessionState.CONNECTED -> Color(0xFF00FF00)
        SessionState.CONNECTING, SessionState.AUTHENTICATING -> Color(0xFFFFAA00)
        SessionState.ERROR -> Color(0xFFFF4444)
        else -> Color.Gray
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Source icon with color indicator
            Box(
                modifier = Modifier.size(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    getSessionIcon(session.getIconType()),
                    contentDescription = session.getSourceLabel(),
                    tint = Color(session.getSourceColor()),
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(Modifier.width(8.dp))

            // Status indicator
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(stateColor, MaterialTheme.shapes.small)
            )

            Spacer(Modifier.width(12.dp))

            // Session info
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = session.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (session.isActive) {
                        Surface(
                            color = Color(0xFF00FF00),
                            shape = MaterialTheme.shapes.extraSmall
                        ) {
                            Text(
                                text = "Active",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Black,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Surface(
                        color = Color(session.getSourceColor()).copy(alpha = 0.3f),
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Text(
                            text = session.getSourceLabel(),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(session.getSourceColor()),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                    Text(
                        text = "${session.username}@${session.host}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = when (session.state) {
                        SessionState.CONNECTED -> "Connected"
                        SessionState.CONNECTING -> "Connecting..."
                        SessionState.AUTHENTICATING -> "Authenticating..."
                        SessionState.DISCONNECTED -> "Disconnected"
                        SessionState.DISCONNECTING -> "Disconnecting..."
                        SessionState.ERROR -> "Error"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = stateColor
                )
            }

            // Pin button
            IconButton(
                onClick = onPin,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    if (session.isPinned) Icons.Default.PushPin else Icons.Default.PushPin,
                    contentDescription = if (session.isPinned) "Unpin session" else "Pin session",
                    tint = if (session.isPinned) Color(0xFFFFD700) else Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp)
                )
            }

            // Close button
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close session",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * Legacy session item for backwards compatibility.
 */
@Deprecated("Use UnifiedSessionItem instead")
@Composable
private fun SessionItem(
    session: SessionInfo,
    onClick: () -> Unit,
    onClose: () -> Unit
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (session.isActive) Color(0xFF1A4A7A) else Color(0xFF0F3460),
        label = "backgroundColor"
    )

    val stateColor = when (session.state) {
        SessionState.CONNECTED -> Color(0xFF00FF00)
        SessionState.CONNECTING, SessionState.AUTHENTICATING -> Color(0xFFFFAA00)
        SessionState.ERROR -> Color(0xFFFF4444)
        else -> Color.Gray
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(stateColor, MaterialTheme.shapes.small)
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = session.connectionName,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (session.isActive) {
                        Surface(
                            color = Color(0xFF00FF00),
                            shape = MaterialTheme.shapes.extraSmall
                        ) {
                            Text(
                                text = "Active",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Black,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Text(
                    text = "${session.username}@${session.host}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when (session.state) {
                        SessionState.CONNECTED -> "Connected"
                        SessionState.CONNECTING -> "Connecting..."
                        SessionState.AUTHENTICATING -> "Authenticating..."
                        SessionState.DISCONNECTED -> "Disconnected"
                        SessionState.DISCONNECTING -> "Disconnecting..."
                        SessionState.ERROR -> "Error"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = stateColor
                )
            }

            IconButton(
                onClick = onClose,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close session",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * Quick actions bar shown at the bottom of the drawer.
 */
@Composable
fun SessionDrawerActions(
    onCloseAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color(0xFF0F3460),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            TextButton(onClick = onCloseAll) {
                Icon(
                    Icons.Default.ClearAll,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("Close All")
            }
        }
    }
}

/**
 * Filter dialog for session filtering.
 */
@Composable
private fun SessionFilterDialog(
    filter: SessionFilter,
    onFilterChange: (SessionFilter) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filter Sessions") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Source type filter
                Text(
                    "Session Types",
                    style = MaterialTheme.typography.labelLarge
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SessionSource.entries.forEach { source ->
                        FilterChip(
                            selected = filter.sources.contains(source),
                            onClick = {
                                val newSources = if (filter.sources.contains(source)) {
                                    filter.sources - source
                                } else {
                                    filter.sources + source
                                }
                                onFilterChange(filter.copy(sources = newSources))
                            },
                            label = { Text(source.name) }
                        )
                    }
                }

                // State filter
                Text(
                    "Session States",
                    style = MaterialTheme.typography.labelLarge
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SessionState.entries.forEach { state ->
                        FilterChip(
                            selected = filter.states.contains(state),
                            onClick = {
                                val newStates = if (filter.states.contains(state)) {
                                    filter.states - state
                                } else {
                                    filter.states + state
                                }
                                onFilterChange(filter.copy(states = newStates))
                            },
                            label = { Text(state.name) }
                        )
                    }
                }

                // Pinned filter
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Show only pinned", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = filter.onlyPinned,
                        onCheckedChange = { onFilterChange(filter.copy(onlyPinned = it)) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                onFilterChange(SessionFilter())
                onDismiss()
            }) {
                Text("Clear All")
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRow(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable () -> Unit
) {
    // Simple implementation using Column with wrapped rows
    Column(
        modifier = modifier,
        verticalArrangement = verticalArrangement
    ) {
        content()
    }
}
