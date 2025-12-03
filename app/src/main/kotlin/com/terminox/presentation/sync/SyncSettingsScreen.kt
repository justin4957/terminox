package com.terminox.presentation.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncDisabled
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.terminox.domain.model.SyncProvider
import com.terminox.domain.model.SyncState
import com.terminox.domain.repository.SyncEvent
import com.terminox.domain.repository.SyncEventType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SyncSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val syncState by viewModel.syncState.collectAsState()
    val syncConfig by viewModel.syncConfig.collectAsState()
    val syncHistory by viewModel.syncHistory.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sync Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (syncConfig.enabled) {
                        IconButton(
                            onClick = { viewModel.syncNow() },
                            enabled = syncState !is SyncState.Syncing
                        ) {
                            if (syncState is SyncState.Syncing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.Sync, contentDescription = "Sync Now")
                            }
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SyncStatusCard(
                    isEnabled = syncConfig.enabled,
                    provider = syncConfig.provider,
                    syncState = syncState,
                    lastSyncTimestamp = syncConfig.lastSyncTimestamp
                )
            }

            if (!syncConfig.enabled) {
                item {
                    Text(
                        text = "Choose a sync provider",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                item {
                    ProviderSelectionCard(
                        provider = SyncProvider.GOOGLE_DRIVE,
                        title = "Google Drive",
                        description = "Sync to your Google account",
                        icon = Icons.Default.Cloud,
                        onClick = { viewModel.selectProvider(SyncProvider.GOOGLE_DRIVE) }
                    )
                }

                item {
                    ProviderSelectionCard(
                        provider = SyncProvider.WEBDAV,
                        title = "WebDAV",
                        description = "Nextcloud, ownCloud, or any WebDAV server",
                        icon = Icons.Default.Storage,
                        onClick = { viewModel.selectProvider(SyncProvider.WEBDAV) }
                    )
                }
            } else {
                item {
                    SyncOptionsCard(
                        autoSyncEnabled = syncConfig.autoSyncEnabled,
                        syncIntervalMinutes = syncConfig.syncIntervalMinutes,
                        onAutoSyncChange = { /* TODO */ },
                        onIntervalChange = { /* TODO */ }
                    )
                }

                item {
                    OutlinedButton(
                        onClick = { viewModel.showDisableConfirmation() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.SyncDisabled, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Disable Sync")
                    }
                }

                if (syncHistory.isNotEmpty()) {
                    item {
                        Text(
                            text = "Sync History",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    items(syncHistory.takeLast(10).reversed()) { event ->
                        SyncHistoryItem(event = event)
                    }
                }
            }
        }
    }

    // Setup Dialog
    if (uiState.showSetupDialog) {
        SyncSetupDialog(
            provider = uiState.selectedProvider ?: SyncProvider.NONE,
            passphrase = uiState.passphrase,
            webDavConfig = uiState.webDavConfig,
            isLoading = uiState.isLoading,
            onPassphraseChange = { viewModel.updatePassphrase(it) },
            onGeneratePassphrase = { viewModel.generatePassphrase() },
            onWebDavConfigChange = { serverUrl, username, password, basePath ->
                viewModel.updateWebDavConfig(serverUrl, username, password, basePath)
            },
            onConfirm = { viewModel.enableSync() },
            onDismiss = { viewModel.dismissSetupDialog() }
        )
    }

    // Disable Confirmation Dialog
    if (uiState.showDisableConfirmation) {
        DisableSyncDialog(
            onConfirm = { deleteRemote ->
                viewModel.disableSync(deleteRemote)
                viewModel.dismissDisableConfirmation()
            },
            onDismiss = { viewModel.dismissDisableConfirmation() }
        )
    }
}

@Composable
private fun SyncStatusCard(
    isEnabled: Boolean,
    provider: SyncProvider,
    syncState: SyncState,
    lastSyncTimestamp: Long?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (syncState) {
                is SyncState.Error -> MaterialTheme.colorScheme.errorContainer
                is SyncState.Success -> MaterialTheme.colorScheme.primaryContainer
                is SyncState.Syncing -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when {
                    syncState is SyncState.Syncing -> Icons.Default.CloudSync
                    syncState is SyncState.Error -> Icons.Default.Error
                    isEnabled -> Icons.Default.Cloud
                    else -> Icons.Default.CloudOff
                },
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = when (syncState) {
                    is SyncState.Error -> MaterialTheme.colorScheme.error
                    is SyncState.Success -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when (syncState) {
                        is SyncState.Syncing -> "Syncing..."
                        is SyncState.Error -> "Sync Error"
                        is SyncState.Success -> "Sync Enabled"
                        is SyncState.Conflict -> "Conflicts Detected"
                        SyncState.Idle -> if (isEnabled) "Ready to Sync" else "Sync Disabled"
                        SyncState.Disabled -> "Sync Disabled"
                    },
                    style = MaterialTheme.typography.titleMedium
                )

                if (isEnabled) {
                    Text(
                        text = "Provider: ${provider.name.replace("_", " ")}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                lastSyncTimestamp?.let { timestamp ->
                    Text(
                        text = "Last sync: ${formatTimestamp(timestamp)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (syncState is SyncState.Error) {
                    Text(
                        text = syncState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderSelectionCard(
    provider: SyncProvider,
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun SyncOptionsCard(
    autoSyncEnabled: Boolean,
    syncIntervalMinutes: Int,
    onAutoSyncChange: (Boolean) -> Unit,
    onIntervalChange: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Sync Options",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(text = "Auto Sync")
                    Text(
                        text = "Sync automatically in background",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = autoSyncEnabled,
                    onCheckedChange = onAutoSyncChange
                )
            }

            if (autoSyncEnabled) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Sync interval: $syncIntervalMinutes minutes",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun SyncHistoryItem(event: SyncEvent) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = when (event.type) {
                SyncEventType.SYNC_COMPLETED -> Icons.Default.Check
                SyncEventType.SYNC_FAILED -> Icons.Default.Error
                SyncEventType.DATA_UPLOADED, SyncEventType.DATA_DOWNLOADED -> Icons.Default.Sync
                else -> Icons.Default.Refresh
            },
            contentDescription = null,
            tint = when (event.type) {
                SyncEventType.SYNC_COMPLETED, SyncEventType.DATA_UPLOADED, SyncEventType.DATA_DOWNLOADED -> MaterialTheme.colorScheme.primary
                SyncEventType.SYNC_FAILED -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(20.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = event.details,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = formatTimestamp(event.timestamp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SyncSetupDialog(
    provider: SyncProvider,
    passphrase: String,
    webDavConfig: com.terminox.domain.model.WebDavConfig,
    isLoading: Boolean,
    onPassphraseChange: (String) -> Unit,
    onGeneratePassphrase: () -> Unit,
    onWebDavConfigChange: (String?, String?, String?, String?) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var showPassphrase by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = {
            Text(
                text = when (provider) {
                    SyncProvider.GOOGLE_DRIVE -> "Set Up Google Drive Sync"
                    SyncProvider.WEBDAV -> "Set Up WebDAV Sync"
                    else -> "Set Up Sync"
                }
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (provider == SyncProvider.WEBDAV) {
                    OutlinedTextField(
                        value = webDavConfig.serverUrl,
                        onValueChange = { onWebDavConfigChange(it, null, null, null) },
                        label = { Text("Server URL") },
                        placeholder = { Text("https://cloud.example.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = webDavConfig.username,
                        onValueChange = { onWebDavConfigChange(null, it, null, null) },
                        label = { Text("Username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = webDavConfig.password,
                        onValueChange = { onWebDavConfigChange(null, null, it, null) },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showPassword) "Hide" else "Show"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = webDavConfig.basePath,
                        onValueChange = { onWebDavConfigChange(null, null, null, it) },
                        label = { Text("Sync Folder Path") },
                        placeholder = { Text("/terminox") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Text(
                    text = "Encryption Passphrase",
                    style = MaterialTheme.typography.labelLarge
                )

                Text(
                    text = "This passphrase encrypts your data before upload. Keep it safe - you'll need it to restore on other devices.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = passphrase,
                    onValueChange = onPassphraseChange,
                    label = { Text("Passphrase") },
                    singleLine = true,
                    visualTransformation = if (showPassphrase) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        Row {
                            IconButton(onClick = { showPassphrase = !showPassphrase }) {
                                Icon(
                                    if (showPassphrase) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showPassphrase) "Hide" else "Show"
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                TextButton(
                    onClick = onGeneratePassphrase,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Generate Strong Passphrase")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isLoading && passphrase.isNotBlank()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Enable Sync")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isLoading
            ) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun DisableSyncDialog(
    onConfirm: (deleteRemote: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var deleteRemoteData by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Disable Sync?") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("Are you sure you want to disable sync?")

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Delete remote data")
                        Text(
                            text = "Also remove data from cloud storage",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = deleteRemoteData,
                        onCheckedChange = { deleteRemoteData = it }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(deleteRemoteData) }
            ) {
                Text("Disable")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun formatTimestamp(timestamp: Long): String {
    val dateFormat = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())
    return dateFormat.format(Date(timestamp))
}
