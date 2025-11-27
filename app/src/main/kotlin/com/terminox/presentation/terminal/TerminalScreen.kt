package com.terminox.presentation.terminal

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.terminox.R
import com.terminox.domain.model.SessionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    connectionId: String,
    onNavigateBack: () -> Unit,
    viewModel: TerminalViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(connectionId) {
        viewModel.connect(connectionId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = uiState.connectionName,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = when (uiState.sessionState) {
                                SessionState.CONNECTING -> stringResource(R.string.terminal_connecting)
                                SessionState.CONNECTED -> stringResource(R.string.terminal_connected)
                                SessionState.DISCONNECTED -> stringResource(R.string.terminal_disconnected)
                                SessionState.ERROR -> stringResource(R.string.terminal_error)
                                else -> ""
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = when (uiState.sessionState) {
                                SessionState.CONNECTED -> MaterialTheme.colorScheme.primary
                                SessionState.ERROR -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (uiState.sessionState) {
                SessionState.CONNECTING, SessionState.AUTHENTICATING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                SessionState.CONNECTED -> {
                    // TODO: Implement terminal canvas in Phase 2
                    Text(
                        text = "Terminal view coming in Phase 2",
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                SessionState.ERROR -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = uiState.error ?: "Unknown error",
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(androidx.compose.ui.unit.dp(16)))
                        Button(onClick = { viewModel.connect(connectionId) }) {
                            Text("Retry")
                        }
                    }
                }
                else -> {
                    Text(
                        text = stringResource(R.string.terminal_disconnected),
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }
    }
}
