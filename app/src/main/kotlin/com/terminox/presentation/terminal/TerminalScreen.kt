package com.terminox.presentation.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.terminox.R
import com.terminox.domain.model.SessionState
import com.terminox.presentation.terminal.components.PasswordDialog
import com.terminox.presentation.terminal.components.TerminalCanvas

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    connectionId: String,
    onNavigateBack: () -> Unit,
    viewModel: TerminalViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    var inputText by remember { mutableStateOf(TextFieldValue("")) }

    LaunchedEffect(connectionId) {
        viewModel.connect(connectionId)
    }

    // Request focus when connected
    LaunchedEffect(uiState.sessionState) {
        if (uiState.sessionState == SessionState.CONNECTED) {
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    // Password dialog
    if (uiState.showPasswordDialog) {
        PasswordDialog(
            hostname = uiState.connectionHost,
            username = uiState.connectionUsername,
            onPasswordEntered = { password ->
                viewModel.authenticateWithPassword(password)
            },
            onDismiss = {
                viewModel.cancelPasswordEntry()
                onNavigateBack()
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = uiState.connectionName.ifEmpty { "Terminal" },
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = when (uiState.sessionState) {
                                SessionState.CONNECTING -> stringResource(R.string.terminal_connecting)
                                SessionState.AUTHENTICATING -> "Authenticating..."
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
                    IconButton(onClick = {
                        viewModel.disconnect()
                        onNavigateBack()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF16213E)
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color(0xFF1A1A2E))
        ) {
            when (uiState.sessionState) {
                SessionState.CONNECTING, SessionState.AUTHENTICATING -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFF00FF00)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (uiState.sessionState == SessionState.CONNECTING)
                                "Connecting to ${uiState.connectionHost}..."
                            else
                                "Authenticating...",
                            color = Color.White
                        )
                    }
                }

                SessionState.CONNECTED -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Terminal display
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            TerminalCanvas(
                                terminalState = uiState.terminalState,
                                modifier = Modifier.fillMaxSize(),
                                onSizeChanged = { columns, rows ->
                                    viewModel.resizeTerminal(columns, rows)
                                },
                                onTap = {
                                    focusRequester.requestFocus()
                                    keyboardController?.show()
                                }
                            )

                            // Hidden text field for keyboard input
                            BasicTextField(
                                value = inputText,
                                onValueChange = { newValue ->
                                    val newText = newValue.text
                                    if (newText.isNotEmpty()) {
                                        // Send each new character
                                        val oldText = inputText.text
                                        if (newText.length > oldText.length) {
                                            val addedText = newText.substring(oldText.length)
                                            viewModel.sendInput(addedText)
                                        }
                                    }
                                    inputText = newValue
                                },
                                modifier = Modifier
                                    .size(1.dp)
                                    .focusRequester(focusRequester),
                                textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp),
                                cursorBrush = SolidColor(Color.Transparent),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.None),
                                keyboardActions = KeyboardActions(
                                    onAny = {
                                        viewModel.sendSpecialKey(SpecialKey.ENTER)
                                        inputText = TextFieldValue("")
                                    }
                                )
                            )
                        }

                        // Extra keys toolbar
                        ExtraKeysToolbar(
                            onKeyPress = { key ->
                                viewModel.sendSpecialKey(key)
                            },
                            onTextInput = { text ->
                                viewModel.sendInput(text)
                            }
                        )
                    }
                }

                SessionState.ERROR -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = uiState.error ?: "Unknown error",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { viewModel.connect(connectionId) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF00FF00)
                            )
                        ) {
                            Text("Retry", color = Color.Black)
                        }
                    }
                }

                else -> {
                    Text(
                        text = stringResource(R.string.terminal_disconnected),
                        modifier = Modifier.align(Alignment.Center),
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun ExtraKeysToolbar(
    onKeyPress: (SpecialKey) -> Unit,
    onTextInput: (String) -> Unit
) {
    var ctrlActive by remember { mutableStateOf(false) }
    var altActive by remember { mutableStateOf(false) }

    Surface(
        color = Color(0xFF16213E),
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // ESC
            ExtraKeyButton(
                text = "ESC",
                onClick = { onKeyPress(SpecialKey.ESCAPE) }
            )

            // TAB
            ExtraKeyButton(
                text = "TAB",
                onClick = { onKeyPress(SpecialKey.TAB) }
            )

            // CTRL (toggle)
            ExtraKeyButton(
                text = "CTRL",
                isActive = ctrlActive,
                onClick = {
                    ctrlActive = !ctrlActive
                    altActive = false
                }
            )

            // ALT (toggle)
            ExtraKeyButton(
                text = "ALT",
                isActive = altActive,
                onClick = {
                    altActive = !altActive
                    ctrlActive = false
                }
            )

            // Arrow keys
            ExtraKeyButton(
                text = "←",
                onClick = { onKeyPress(SpecialKey.ARROW_LEFT) }
            )
            ExtraKeyButton(
                text = "→",
                onClick = { onKeyPress(SpecialKey.ARROW_RIGHT) }
            )
            ExtraKeyButton(
                text = "↑",
                onClick = { onKeyPress(SpecialKey.ARROW_UP) }
            )
            ExtraKeyButton(
                text = "↓",
                onClick = { onKeyPress(SpecialKey.ARROW_DOWN) }
            )
        }
    }
}

@Composable
fun ExtraKeyButton(
    text: String,
    isActive: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = if (isActive) Color(0xFF00FF00) else Color(0xFF0F3460),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.padding(2.dp)
    ) {
        Text(
            text = text,
            color = if (isActive) Color.Black else Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}
