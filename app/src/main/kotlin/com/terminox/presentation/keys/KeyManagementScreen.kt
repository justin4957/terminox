package com.terminox.presentation.keys

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.terminox.R
import com.terminox.domain.model.KeyType
import com.terminox.domain.model.SshKey
import com.terminox.security.BiometricStatus
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyManagementScreen(
    onNavigateBack: () -> Unit,
    viewModel: KeyManagementViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.copiedToClipboard) {
        if (uiState.copiedToClipboard) {
            snackbarHostState.showSnackbar("Public key copied to clipboard")
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.keys_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.showGenerateDialog() }
            ) {
                Icon(Icons.Default.Add, contentDescription = "Generate Key")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (uiState.keys.isEmpty()) {
                EmptyKeysContent(
                    onGenerateClick = { viewModel.showGenerateDialog() },
                    onImportClick = { viewModel.showImportDialog() },
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                KeysList(
                    keys = uiState.keys,
                    onKeyClick = { viewModel.showKeyDetail(it) },
                    onCopyClick = { key ->
                        clipboardManager.setText(AnnotatedString(viewModel.getPublicKeyForClipboard(key)))
                        viewModel.onCopiedToClipboard()
                    },
                    onDeleteClick = { viewModel.showDeleteConfirmDialog(it) }
                )
            }
        }
    }

    // Generate Key Dialog
    if (uiState.showGenerateDialog) {
        GenerateKeyDialog(
            biometricAvailable = uiState.biometricStatus.isAvailable,
            isGenerating = uiState.generationInProgress,
            onGenerate = { name, type, requireBiometric ->
                viewModel.generateKey(name, type, requireBiometric)
            },
            onDismiss = { viewModel.hideGenerateDialog() }
        )
    }

    // Import Key Dialog
    if (uiState.showImportDialog) {
        ImportKeyDialog(
            biometricAvailable = uiState.biometricStatus.isAvailable,
            isImporting = uiState.generationInProgress,
            onImport = { name, privateKey, requireBiometric ->
                viewModel.importKey(name, privateKey, requireBiometric)
            },
            onDismiss = { viewModel.hideImportDialog() }
        )
    }

    // Delete Confirmation Dialog
    uiState.showDeleteConfirmDialog?.let { key ->
        DeleteKeyConfirmDialog(
            key = key,
            onConfirm = { viewModel.deleteKey(key) },
            onDismiss = { viewModel.hideDeleteConfirmDialog() }
        )
    }

    // Key Detail Dialog
    uiState.showKeyDetailDialog?.let { key ->
        KeyDetailDialog(
            key = key,
            onCopy = {
                clipboardManager.setText(AnnotatedString(viewModel.getPublicKeyForClipboard(key)))
                viewModel.onCopiedToClipboard()
            },
            onDismiss = { viewModel.hideKeyDetail() }
        )
    }
}

@Composable
private fun EmptyKeysContent(
    onGenerateClick: () -> Unit,
    onImportClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            Icons.Default.Key,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.keys_empty),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = "Generate a new SSH key or import an existing one",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onImportClick) {
                Icon(Icons.Default.FileUpload, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Import")
            }
            Button(onClick = onGenerateClick) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Generate")
            }
        }
    }
}

@Composable
private fun KeysList(
    keys: List<SshKey>,
    onKeyClick: (SshKey) -> Unit,
    onCopyClick: (SshKey) -> Unit,
    onDeleteClick: (SshKey) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(keys, key = { it.id }) { key ->
            KeyListItem(
                key = key,
                onClick = { onKeyClick(key) },
                onCopyClick = { onCopyClick(key) },
                onDeleteClick = { onDeleteClick(key) }
            )
        }
    }
}

@Composable
private fun KeyListItem(
    key: SshKey,
    onClick: () -> Unit,
    onCopyClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (key.requiresBiometric) Icons.Default.Fingerprint else Icons.Default.Key,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = key.name,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "${key.type.displayName} • ${dateFormat.format(Date(key.createdAt))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = key.fingerprint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onCopyClick) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy public key")
            }
            IconButton(onClick = onDeleteClick) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete key",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GenerateKeyDialog(
    biometricAvailable: Boolean,
    isGenerating: Boolean,
    onGenerate: (name: String, type: KeyType, requireBiometric: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(KeyType.ED25519) }
    var requireBiometric by remember { mutableStateOf(biometricAvailable) }
    var expandedDropdown by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isGenerating) onDismiss() },
        title = { Text("Generate SSH Key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Key Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isGenerating
                )

                ExposedDropdownMenuBox(
                    expanded = expandedDropdown,
                    onExpandedChange = { if (!isGenerating) expandedDropdown = it }
                ) {
                    OutlinedTextField(
                        value = selectedType.displayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Key Type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedDropdown) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        enabled = !isGenerating
                    )
                    ExposedDropdownMenu(
                        expanded = expandedDropdown,
                        onDismissRequest = { expandedDropdown = false }
                    ) {
                        KeyType.entries.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type.displayName) },
                                onClick = {
                                    selectedType = type
                                    expandedDropdown = false
                                }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Require Biometric", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = if (biometricAvailable) "Protect with fingerprint" else "Biometric not available",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = requireBiometric,
                        onCheckedChange = { requireBiometric = it },
                        enabled = biometricAvailable && !isGenerating
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onGenerate(name, selectedType, requireBiometric) },
                enabled = name.isNotBlank() && !isGenerating
            ) {
                if (isGenerating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text("Generate")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isGenerating
            ) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ImportKeyDialog(
    biometricAvailable: Boolean,
    isImporting: Boolean,
    onImport: (name: String, privateKey: String, requireBiometric: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf("") }
    var requireBiometric by remember { mutableStateOf(biometricAvailable) }

    AlertDialog(
        onDismissRequest = { if (!isImporting) onDismiss() },
        title = { Text("Import SSH Key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Key Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isImporting
                )

                OutlinedTextField(
                    value = privateKey,
                    onValueChange = { privateKey = it },
                    label = { Text("Private Key (PEM)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    enabled = !isImporting,
                    placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----\n...\n-----END OPENSSH PRIVATE KEY-----") }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Require Biometric", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = if (biometricAvailable) "Protect with fingerprint" else "Biometric not available",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = requireBiometric,
                        onCheckedChange = { requireBiometric = it },
                        enabled = biometricAvailable && !isImporting
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onImport(name, privateKey, requireBiometric) },
                enabled = name.isNotBlank() && privateKey.isNotBlank() && !isImporting
            ) {
                if (isImporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text("Import")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isImporting
            ) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun DeleteKeyConfirmDialog(
    key: SshKey,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Delete Key?") },
        text = {
            Text("Are you sure you want to delete \"${key.name}\"? This action cannot be undone and any connections using this key will no longer work.")
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun KeyDetailDialog(
    key: SshKey,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMMM d, yyyy 'at' HH:mm", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(key.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DetailRow("Type", key.type.displayName)
                DetailRow("Created", dateFormat.format(Date(key.createdAt)))
                DetailRow("Fingerprint", key.fingerprint)
                DetailRow("Biometric", if (key.requiresBiometric) "Required" else "Not required")

                HorizontalDivider()

                Text("Public Key", style = MaterialTheme.typography.labelMedium)
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = key.publicKey,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onCopy) {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Copy Public Key")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
