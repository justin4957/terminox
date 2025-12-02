package com.terminox.presentation.connections

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.terminox.R
import com.terminox.domain.model.Connection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsScreen(
    onConnectionClick: (String) -> Unit,
    onNavigateToKeys: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToQrPairing: () -> Unit,
    onNavigateToDiscovery: () -> Unit,
    onNavigateToHistory: () -> Unit = {},
    viewModel: ConnectionsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var connectionToEdit by remember { mutableStateOf<Connection?>(null) }
    var showDeleteConfirmation by remember { mutableStateOf<Connection?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.connections_title)) },
                actions = {
                    IconButton(onClick = onNavigateToDiscovery) {
                        Icon(Icons.Default.Wifi, contentDescription = "Discover Servers")
                    }
                    IconButton(onClick = onNavigateToQrPairing) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan QR Code")
                    }
                    IconButton(onClick = onNavigateToHistory) {
                        Icon(Icons.Default.History, contentDescription = "Connection History")
                    }
                    IconButton(onClick = onNavigateToKeys) {
                        Icon(Icons.Default.Key, contentDescription = stringResource(R.string.nav_keys))
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.nav_settings))
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.connections_add))
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
                uiState.connections.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.connections_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(uiState.connections, key = { it.id }) { connection ->
                            ConnectionCard(
                                connection = connection,
                                onClick = { onConnectionClick(connection.id) },
                                onEdit = { connectionToEdit = connection },
                                onDelete = { showDeleteConfirmation = connection }
                            )
                        }
                    }
                }
            }
        }
    }

    // Add connection dialog
    if (showAddDialog) {
        ConnectionDialog(
            existingConnection = null,
            onDismiss = { showAddDialog = false },
            onSave = { connection ->
                viewModel.saveConnection(connection)
                showAddDialog = false
            }
        )
    }

    // Edit connection dialog
    connectionToEdit?.let { connection ->
        ConnectionDialog(
            existingConnection = connection,
            onDismiss = { connectionToEdit = null },
            onUpdate = { updatedConnection ->
                viewModel.updateConnection(updatedConnection)
                connectionToEdit = null
            }
        )
    }

    // Delete confirmation dialog
    showDeleteConfirmation?.let { connection ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = null },
            title = { Text(stringResource(R.string.delete)) },
            text = {
                Text("Delete connection \"${connection.name}\"?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteConnection(connection.id)
                        showDeleteConfirmation = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
fun ConnectionCard(
    connection: Connection,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
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
                Text(
                    text = connection.name,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${connection.username}@${connection.host}:${connection.port}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = connection.protocol.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    SecurityLevelBadge(level = connection.securityLevel)
                }
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
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit)) },
                        onClick = {
                            showMenu = false
                            onEdit()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Edit, contentDescription = null)
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.delete),
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            showMenu = false
                            onDelete()
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
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
