package com.terminox.presentation.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.terminox.R
import com.terminox.domain.model.HostVerificationResult
import com.terminox.security.OverlayProtectedView

/**
 * Dialog shown when connecting to a new server for the first time.
 * Implements Trust On First Use (TOFU) verification.
 * Protected against overlay attacks (AV-13).
 */
@Composable
fun NewHostVerificationDialog(
    hostInfo: HostVerificationResult.NewHost,
    onTrustAndConnect: () -> Unit,
    onReject: () -> Unit
) {
    // Apply overlay protection (AV-13: Overlay Attack Protection)
    OverlayProtectedView()

    AlertDialog(
        onDismissRequest = onReject,
        icon = {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = stringResource(R.string.host_verification_new_title),
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.host_verification_new_message),
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Server info card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        InfoRow(
                            label = stringResource(R.string.host_verification_server),
                            value = "${hostInfo.host}:${hostInfo.port}"
                        )
                        InfoRow(
                            label = stringResource(R.string.host_verification_key_type),
                            value = hostInfo.keyType
                        )
                        Column {
                            Text(
                                text = stringResource(R.string.host_verification_fingerprint),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = hostInfo.fingerprint,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onTrustAndConnect) {
                Text(stringResource(R.string.host_verification_trust_connect))
            }
        },
        dismissButton = {
            TextButton(onClick = onReject) {
                Text(stringResource(R.string.host_verification_reject))
            }
        }
    )
}

/**
 * Dialog shown when a server's fingerprint has changed since last connection.
 * This is a security warning that could indicate a MITM attack.
 */
@Composable
fun FingerprintChangedDialog(
    hostInfo: HostVerificationResult.FingerprintChanged,
    onUpdateAndConnect: () -> Unit,
    onReject: () -> Unit
) {
    // Apply overlay protection (AV-13: Overlay Attack Protection)
    OverlayProtectedView()

    AlertDialog(
        onDismissRequest = onReject,
        icon = {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text(
                text = stringResource(R.string.host_verification_changed_title),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.error
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.host_verification_changed_message),
                    style = MaterialTheme.typography.bodyMedium
                )

                // Pinned host warning
                if (hostInfo.isPinned) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.host_verification_pinned_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Server info card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        InfoRow(
                            label = stringResource(R.string.host_verification_server),
                            value = "${hostInfo.host}:${hostInfo.port}"
                        )
                        InfoRow(
                            label = stringResource(R.string.host_verification_key_type),
                            value = hostInfo.keyType
                        )

                        // Previous fingerprint
                        Column {
                            Text(
                                text = stringResource(R.string.host_verification_previous_fingerprint),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = hostInfo.storedFingerprint,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }

                        // Current fingerprint
                        Column {
                            Text(
                                text = stringResource(R.string.host_verification_current_fingerprint),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = hostInfo.currentFingerprint,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onUpdateAndConnect) {
                Text(
                    text = stringResource(R.string.host_verification_update_connect),
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onReject) {
                Text(stringResource(R.string.host_verification_reject))
            }
        }
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}
