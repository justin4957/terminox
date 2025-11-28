package com.terminox.presentation.connections

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.terminox.R
import com.terminox.domain.model.AuthMethod
import com.terminox.domain.model.Connection
import com.terminox.domain.model.ProtocolType
import java.util.UUID

/**
 * Dialog for adding or editing a connection.
 *
 * @param existingConnection If provided, the dialog will be in edit mode and pre-populate fields
 * @param onDismiss Called when the dialog is dismissed
 * @param onSave Called when the connection is saved (for new connections)
 * @param onUpdate Called when an existing connection is updated (for edit mode)
 */
@Composable
fun ConnectionDialog(
    existingConnection: Connection? = null,
    onDismiss: () -> Unit,
    onSave: (Connection) -> Unit = {},
    onUpdate: (Connection) -> Unit = {}
) {
    val isEditMode = existingConnection != null

    var name by remember { mutableStateOf(existingConnection?.name ?: "") }
    var host by remember { mutableStateOf(existingConnection?.host ?: "") }
    var port by remember { mutableStateOf(existingConnection?.port?.toString() ?: "22") }
    var username by remember { mutableStateOf(existingConnection?.username ?: "") }
    var selectedProtocol by remember { mutableStateOf(existingConnection?.protocol ?: ProtocolType.SSH) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (isEditMode) stringResource(R.string.edit)
                else stringResource(R.string.connections_add)
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.connection_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(stringResource(R.string.connection_host)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { char -> char.isDigit() } },
                    label = { Text(stringResource(R.string.connection_port)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.connection_username)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Protocol selection
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ProtocolType.entries.forEach { protocol ->
                        FilterChip(
                            selected = selectedProtocol == protocol,
                            onClick = { selectedProtocol = protocol },
                            label = { Text(protocol.name) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (isEditMode) {
                        // Update existing connection, preserving authMethod and keyId
                        val updatedConnection = existingConnection!!.copy(
                            name = name.ifBlank { "$username@$host" },
                            host = host,
                            port = port.toIntOrNull() ?: 22,
                            username = username,
                            protocol = selectedProtocol
                        )
                        onUpdate(updatedConnection)
                    } else {
                        // Create new connection
                        val newConnection = Connection(
                            id = UUID.randomUUID().toString(),
                            name = name.ifBlank { "$username@$host" },
                            host = host,
                            port = port.toIntOrNull() ?: 22,
                            username = username,
                            protocol = selectedProtocol,
                            authMethod = AuthMethod.Password
                        )
                        onSave(newConnection)
                    }
                },
                enabled = host.isNotBlank() && username.isNotBlank()
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/**
 * Legacy alias for backward compatibility.
 * Use [ConnectionDialog] for new code.
 */
@Composable
fun AddConnectionDialog(
    onDismiss: () -> Unit,
    onSave: (Connection) -> Unit
) {
    ConnectionDialog(
        existingConnection = null,
        onDismiss = onDismiss,
        onSave = onSave
    )
}
