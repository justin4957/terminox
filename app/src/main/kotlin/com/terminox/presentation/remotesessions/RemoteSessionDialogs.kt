package com.terminox.presentation.remotesessions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.terminox.domain.model.RemoteSessionFilter
import com.terminox.domain.model.RemoteSessionState
import com.terminox.domain.model.SessionType

/**
 * Dialog for filtering remote sessions by state, type, and search query.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterDialog(
    currentFilter: RemoteSessionFilter,
    onDismiss: () -> Unit,
    onApply: (RemoteSessionFilter) -> Unit,
    onClear: () -> Unit
) {
    var selectedState by remember { mutableStateOf(currentFilter.state) }
    var selectedType by remember { mutableStateOf(currentFilter.sessionType) }
    var searchQuery by remember { mutableStateOf(currentFilter.searchQuery) }
    var onlyReconnectable by remember { mutableStateOf(currentFilter.onlyReconnectable) }
    var onlyRecentlyActive by remember { mutableStateOf(currentFilter.onlyRecentlyActive) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filter Sessions") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Search query
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search") },
                    placeholder = { Text("Session title or ID") },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                // State filter
                Text(
                    text = "State",
                    style = MaterialTheme.typography.labelMedium
                )
                FilterChipGroup(
                    items = listOf(null) + RemoteSessionState.entries,
                    selectedItem = selectedState,
                    onItemSelected = { selectedState = it },
                    itemLabel = { it?.getDisplayName() ?: "All" }
                )

                // Type filter
                Text(
                    text = "Session Type",
                    style = MaterialTheme.typography.labelMedium
                )
                FilterChipGroup(
                    items = listOf(null) + SessionType.entries,
                    selectedItem = selectedType,
                    onItemSelected = { selectedType = it },
                    itemLabel = { it?.displayName ?: "All" }
                )

                // Additional filters
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Only reconnectable",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = onlyReconnectable,
                        onCheckedChange = { onlyReconnectable = it }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Only recently active",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = onlyRecentlyActive,
                        onCheckedChange = { onlyRecentlyActive = it }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onApply(
                        RemoteSessionFilter(
                            state = selectedState,
                            sessionType = selectedType,
                            searchQuery = searchQuery,
                            onlyReconnectable = onlyReconnectable,
                            onlyRecentlyActive = onlyRecentlyActive
                        )
                    )
                }
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onClear) {
                    Text("Clear")
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}

/**
 * Generic filter chip group for selecting one item from a list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> FilterChipGroup(
    items: List<T>,
    selectedItem: T?,
    onItemSelected: (T?) -> Unit,
    itemLabel: (T?) -> String
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items.forEach { item ->
            FilterChip(
                selected = selectedItem == item,
                onClick = { onItemSelected(item) },
                label = { Text(itemLabel(item)) }
            )
        }
    }
}

/**
 * Dialog for creating a new remote terminal session.
 */
@Composable
fun CreateSessionDialog(
    onDismiss: () -> Unit,
    onCreate: (
        shell: String?,
        columns: Int,
        rows: Int,
        workingDirectory: String?,
        environment: Map<String, String>
    ) -> Unit
) {
    var shell by remember { mutableStateOf("") }
    var columns by remember { mutableStateOf("80") }
    var rows by remember { mutableStateOf("24") }
    var workingDirectory by remember { mutableStateOf("") }
    var showAdvanced by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create New Session") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Configure the new terminal session",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Shell
                OutlinedTextField(
                    value = shell,
                    onValueChange = { shell = it },
                    label = { Text("Shell (optional)") },
                    placeholder = { Text("e.g., /bin/bash") },
                    modifier = Modifier.fillMaxWidth()
                )

                // Terminal dimensions
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = columns,
                        onValueChange = { if (it.all { char -> char.isDigit() }) columns = it },
                        label = { Text("Columns") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = rows,
                        onValueChange = { if (it.all { char -> char.isDigit() }) rows = it },
                        label = { Text("Rows") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }

                // Advanced options toggle
                TextButton(
                    onClick = { showAdvanced = !showAdvanced }
                ) {
                    Text(if (showAdvanced) "Hide Advanced" else "Show Advanced")
                }

                if (showAdvanced) {
                    OutlinedTextField(
                        value = workingDirectory,
                        onValueChange = { workingDirectory = it },
                        label = { Text("Working Directory (optional)") },
                        placeholder = { Text("e.g., /home/user/projects") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsedColumns = columns.toIntOrNull() ?: 80
                    val parsedRows = rows.toIntOrNull() ?: 24
                    onCreate(
                        shell.ifBlank { null },
                        parsedColumns,
                        parsedRows,
                        workingDirectory.ifBlank { null },
                        emptyMap() // Environment variables not currently exposed in UI
                    )
                }
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
