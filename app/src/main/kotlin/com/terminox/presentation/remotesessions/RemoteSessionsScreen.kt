package com.terminox.presentation.remotesessions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.terminox.domain.model.RemoteSession
import com.terminox.domain.model.RemoteSessionState

/**
 * Screen for displaying and managing remote terminal sessions from desktop agents.
 * Shows list of sessions with metadata, supports filtering, and provides session actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteSessionsScreen(
    onSessionClick: (String) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: RemoteSessionsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Remote Sessions") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleFilterDialog() }) {
                        Icon(Icons.Default.FilterList, contentDescription = "Filter")
                    }
                    IconButton(
                        onClick = { viewModel.refreshSessions() },
                        enabled = !uiState.isRefreshing
                    ) {
                        if (uiState.isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (uiState.isAgentConnected) {
                FloatingActionButton(onClick = { viewModel.toggleCreateSessionDialog() }) {
                    Icon(Icons.Default.Add, contentDescription = "Create Session")
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                !uiState.isAgentConnected -> {
                    EmptyStateMessage(
                        icon = Icons.Default.CloudOff,
                        message = "No agent connected",
                        description = "Connect to a desktop agent to view and manage terminal sessions",
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                uiState.filteredSessions.isEmpty() -> {
                    EmptyStateMessage(
                        icon = Icons.Default.Terminal,
                        message = "No sessions found",
                        description = if (uiState.filter.searchQuery.isNotBlank() ||
                                        uiState.filter.state != null) {
                            "Try adjusting your filters"
                        } else {
                            "Create a new terminal session to get started"
                        },
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(uiState.filteredSessions, key = { it.id }) { session ->
                            RemoteSessionCard(
                                session = session,
                                onClick = { onSessionClick(session.id) },
                                onAttach = { viewModel.attachToSession(session.id) },
                                onReconnect = { viewModel.reconnectSession(session.id) },
                                onClose = { viewModel.closeSession(session.id) }
                            )
                        }
                    }
                }
            }

            // Error snackbar
            uiState.error?.let { error ->
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    action = {
                        TextButton(onClick = { viewModel.clearError() }) {
                            Text("Dismiss")
                        }
                    }
                ) {
                    Text(error)
                }
            }
        }
    }

    // Filter dialog
    if (uiState.showFilterDialog) {
        FilterDialog(
            currentFilter = uiState.filter,
            onDismiss = { viewModel.toggleFilterDialog() },
            onApply = { filter ->
                viewModel.updateFilter(filter)
            },
            onClear = { viewModel.clearFilter() }
        )
    }

    // Create session dialog
    if (uiState.showCreateSessionDialog) {
        CreateSessionDialog(
            onDismiss = { viewModel.toggleCreateSessionDialog() },
            onCreate = { shell, columns, rows, workingDir, env ->
                viewModel.createSession(
                    shell = shell,
                    columns = columns,
                    rows = rows,
                    workingDirectory = workingDir,
                    environment = env
                )
            }
        )
    }
}

@Composable
fun RemoteSessionCard(
    session: RemoteSession,
    onClick: () -> Unit,
    onAttach: () -> Unit,
    onReconnect: () -> Unit,
    onClose: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = session.getDisplayTitle(),
                        style = MaterialTheme.typography.titleMedium
                    )
                    SessionStateBadge(state = session.state)
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (session.dimensions != null) {
                        InfoChip(
                            icon = Icons.Default.AspectRatio,
                            text = session.dimensions
                        )
                    }
                    InfoChip(
                        icon = Icons.Default.Schedule,
                        text = session.getAge()
                    )
                    InfoChip(
                        icon = Icons.Default.Terminal,
                        text = session.sessionType.displayName
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = session.getTimeSinceActivity(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

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
                    if (session.state == RemoteSessionState.ACTIVE) {
                        DropdownMenuItem(
                            text = { Text("Attach") },
                            onClick = {
                                showMenu = false
                                onAttach()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Link, contentDescription = null)
                            }
                        )
                    }

                    if (session.isReconnectable()) {
                        DropdownMenuItem(
                            text = { Text("Reconnect") },
                            onClick = {
                                showMenu = false
                                onReconnect()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                            }
                        )
                    }

                    if (session.state != RemoteSessionState.TERMINATED) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Close",
                                    color = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                showMenu = false
                                onClose()
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Close,
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

@Composable
fun SessionStateBadge(state: RemoteSessionState) {
    val (color, icon) = when (state) {
        RemoteSessionState.CREATED -> MaterialTheme.colorScheme.secondary to Icons.Default.HourglassEmpty
        RemoteSessionState.ACTIVE -> MaterialTheme.colorScheme.primary to Icons.Default.CheckCircle
        RemoteSessionState.DISCONNECTED -> MaterialTheme.colorScheme.tertiary to Icons.Default.Warning
        RemoteSessionState.TERMINATED -> MaterialTheme.colorScheme.error to Icons.Default.Cancel
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
                modifier = Modifier.size(14.dp),
                tint = color
            )
            Text(
                text = state.getDisplayName(),
                style = MaterialTheme.typography.labelSmall,
                color = color
            )
        }
    }
}

@Composable
fun InfoChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun EmptyStateMessage(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    message: String,
    description: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = message,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
