package com.terminox.presentation.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.terminox.domain.model.SessionState

/**
 * Represents a terminal session in the drawer.
 */
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
 * Session drawer for managing multiple terminal sessions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDrawer(
    sessions: List<SessionInfo>,
    onSessionClick: (SessionInfo) -> Unit,
    onSessionClose: (SessionInfo) -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier
) {
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
                            text = "${sessions.size} active",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                    FilledTonalIconButton(
                        onClick = onNewSession,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = Color(0xFF00FF00),
                            contentColor = Color.Black
                        )
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "New Session")
                    }
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
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(sessions, key = { it.sessionId }) { session ->
                        SessionItem(
                            session = session,
                            onClick = { onSessionClick(session) },
                            onClose = { onSessionClose(session) }
                        )
                    }
                }
            }
        }
    }
}

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
                        SessionState.ERROR -> "Error"
                        else -> "Unknown"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = stateColor
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
