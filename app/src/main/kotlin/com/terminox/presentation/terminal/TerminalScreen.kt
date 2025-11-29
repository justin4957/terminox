package com.terminox.presentation.terminal

import android.app.Activity
import android.content.Context
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.terminox.R
import com.terminox.domain.model.HostVerificationResult
import com.terminox.domain.model.SessionState
import com.terminox.domain.model.TerminalSettings
import com.terminox.presentation.navigation.SessionDrawer
import com.terminox.presentation.navigation.SessionInfo
import com.terminox.presentation.settings.TerminalSettingsSheet
import com.terminox.presentation.terminal.components.ClipboardAction
import com.terminox.presentation.terminal.components.ClipboardActionBar
import com.terminox.presentation.terminal.components.EnhancedKeyboardBar
import com.terminox.presentation.terminal.components.PasswordDialog
import com.terminox.presentation.terminal.components.TerminalCanvas
import com.terminox.presentation.terminal.components.TerminalClipboardHelper
import com.terminox.presentation.terminal.components.TerminalGesture
import com.terminox.presentation.terminal.components.TerminalTab
import com.terminox.presentation.terminal.components.TerminalTabBar
import com.terminox.presentation.terminal.components.TextSelection
import com.terminox.presentation.terminal.components.rememberCellDimensions
import com.terminox.presentation.terminal.components.screenPositionToCell
import kotlinx.coroutines.launch

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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current

    var inputText by remember { mutableStateOf(TextFieldValue("")) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    // Get settings from state
    val settings = uiState.settings
    val theme = settings.theme

    // Terminal display state (use settings.fontSize as default)
    var fontSize by remember { mutableFloatStateOf(settings.fontSize) }
    var scrollOffset by remember { mutableIntStateOf(0) }
    var selection by remember { mutableStateOf<TextSelection?>(null) }
    var showClipboardBar by remember { mutableStateOf(false) }

    // Clipboard helper
    val clipboardHelper = remember { TerminalClipboardHelper(context) }

    // Immersive mode effect
    LaunchedEffect(settings.immersiveMode) {
        val activity = context as? Activity ?: return@LaunchedEffect
        val window = activity.window
        val windowInsetsController = WindowCompat.getInsetsController(window, view)

        if (settings.immersiveMode) {
            windowInsetsController.apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Keep screen on effect
    LaunchedEffect(settings.keepScreenOn) {
        val activity = context as? Activity ?: return@LaunchedEffect
        if (settings.keepScreenOn) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Sync fontSize with settings when settings change
    LaunchedEffect(settings.fontSize) {
        fontSize = settings.fontSize
    }

    // Cell dimensions for gesture calculations
    val cellDimensions = rememberCellDimensions(fontSize)

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

    // Host verification dialogs (TOFU)
    uiState.hostVerification?.let { verification ->
        when (verification) {
            is HostVerificationResult.NewHost -> {
                NewHostVerificationDialog(
                    hostInfo = verification,
                    onTrustAndConnect = { viewModel.acceptNewHost() },
                    onReject = {
                        viewModel.rejectHostVerification()
                        onNavigateBack()
                    }
                )
            }
            is HostVerificationResult.FingerprintChanged -> {
                FingerprintChangedDialog(
                    hostInfo = verification,
                    onUpdateAndConnect = { viewModel.acceptFingerprintChange() },
                    onReject = {
                        viewModel.rejectHostVerification()
                        onNavigateBack()
                    }
                )
            }
            else -> { /* Trusted - no dialog needed */ }
        }
    }

    // Convert sessions for drawer
    val sessionList = uiState.sessions.map { session ->
        SessionInfo(
            sessionId = session.sessionId,
            connectionId = session.connectionId,
            connectionName = session.connectionName,
            host = session.host,
            username = session.username,
            state = session.state,
            isActive = session.sessionId == uiState.activeSessionId
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = uiState.sessionState == SessionState.CONNECTED,
        drawerContent = {
            SessionDrawer(
                sessions = sessionList,
                onSessionClick = { sessionInfo ->
                    viewModel.switchSession(sessionInfo.sessionId)
                    scope.launch { drawerState.close() }
                },
                onSessionClose = { sessionInfo ->
                    viewModel.closeSession(sessionInfo.sessionId)
                },
                onNewSession = {
                    scope.launch { drawerState.close() }
                    // Navigate to connection picker or create new session
                }
            )
        }
    ) {
        Scaffold(
            topBar = {
                if (!settings.immersiveMode) {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    text = uiState.connectionName.ifEmpty { "Terminal" },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = theme.toolbarForeground
                                )
                                Text(
                                    text = when (uiState.sessionState) {
                                        SessionState.CONNECTING -> stringResource(R.string.terminal_connecting)
                                        SessionState.AUTHENTICATING -> "Authenticating..."
                                        SessionState.CONNECTED -> "${stringResource(R.string.terminal_connected)} (${uiState.sessions.size} sessions)"
                                        SessionState.DISCONNECTED -> stringResource(R.string.terminal_disconnected)
                                        SessionState.ERROR -> stringResource(R.string.terminal_error)
                                        else -> ""
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when (uiState.sessionState) {
                                        SessionState.CONNECTED -> theme.accent
                                        SessionState.ERROR -> MaterialTheme.colorScheme.error
                                        else -> theme.toolbarForeground.copy(alpha = 0.7f)
                                    }
                                )
                            }
                        },
                        navigationIcon = {
                            if (uiState.sessions.size > 1) {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(Icons.Default.Menu, contentDescription = "Sessions", tint = theme.toolbarForeground)
                                }
                            } else {
                                IconButton(onClick = {
                                    viewModel.disconnect()
                                    onNavigateBack()
                                }) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back",
                                        tint = theme.toolbarForeground
                                    )
                                }
                            }
                        },
                        actions = {
                            IconButton(onClick = { viewModel.showSettings() }) {
                                Icon(
                                    Icons.Default.Settings,
                                    contentDescription = "Settings",
                                    tint = theme.toolbarForeground
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = theme.toolbarBackground
                        )
                    )
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(theme.background)
            ) {
                when (uiState.sessionState) {
                    SessionState.CONNECTING, SessionState.AUTHENTICATING -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(
                                color = theme.accent
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = if (uiState.sessionState == SessionState.CONNECTING)
                                    "Connecting to ${uiState.connectionHost}..."
                                else
                                    "Authenticating...",
                                color = theme.foreground
                            )
                        }
                    }

                    SessionState.CONNECTED -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // Tab bar (when multiple sessions and tab bar is enabled)
                            if (settings.showTabBar && uiState.sessions.size > 1) {
                                val terminalTabs = uiState.sessions.map { session ->
                                    TerminalTab(
                                        id = session.sessionId,
                                        title = session.connectionName.ifEmpty { "Shell" },
                                        isActive = session.sessionId == uiState.activeSessionId,
                                        hasActivity = false, // TODO: Track activity per session
                                        workingDirectory = null
                                    )
                                }
                                TerminalTabBar(
                                    tabs = terminalTabs,
                                    activeTabId = uiState.activeSessionId,
                                    theme = theme,
                                    onTabSelected = { tabId ->
                                        viewModel.switchSession(tabId)
                                    },
                                    onTabClose = { tabId ->
                                        viewModel.closeSession(tabId)
                                    },
                                    onNewTab = {
                                        // TODO: Open connection picker or create new shell
                                    }
                                )
                            }

                            // Terminal display
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                            ) {
                                TerminalCanvas(
                                    terminalState = uiState.terminalState,
                                    modifier = Modifier.fillMaxSize(),
                                    fontSize = fontSize,
                                    selection = selection,
                                    scrollOffset = scrollOffset,
                                    onSizeChanged = { columns, rows ->
                                        viewModel.resizeTerminal(columns, rows)
                                    },
                                    onTap = {
                                        // Clear selection on tap
                                        selection = null
                                        showClipboardBar = false
                                        focusRequester.requestFocus()
                                        keyboardController?.show()
                                    },
                                    onGesture = { gesture ->
                                        handleTerminalGesture(
                                            gesture = gesture,
                                            cellDimensions = cellDimensions,
                                            currentFontSize = fontSize,
                                            onFontSizeChange = { newSize ->
                                                val clampedSize = newSize.coerceIn(
                                                    TerminalSettings.MIN_FONT_SIZE,
                                                    TerminalSettings.MAX_FONT_SIZE
                                                )
                                                fontSize = clampedSize
                                                // Also update settings for persistence
                                                viewModel.updateSettings(settings.copy(fontSize = clampedSize))
                                            },
                                            onScrollChange = { delta ->
                                                scrollOffset = (scrollOffset + delta.toInt())
                                                    .coerceAtLeast(0)
                                            },
                                            onSelectionChange = { newSelection ->
                                                selection = newSelection
                                                showClipboardBar = newSelection != null
                                            },
                                            onOpenDrawer = {
                                                scope.launch { drawerState.open() }
                                            },
                                            terminalState = uiState.terminalState
                                        )
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

                                // Clipboard action bar
                                ClipboardActionBar(
                                    visible = showClipboardBar,
                                    hasSelection = selection != null && !selection!!.isEmpty(),
                                    onAction = { action ->
                                        handleClipboardAction(
                                            action = action,
                                            selection = selection,
                                            terminalState = uiState.terminalState,
                                            clipboardHelper = clipboardHelper,
                                            onPaste = { text -> viewModel.sendInput(text) },
                                            onSelectAll = { newSelection ->
                                                selection = newSelection
                                            },
                                            onSelectionCleared = {
                                                selection = null
                                                showClipboardBar = false
                                            }
                                        )
                                    },
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(top = 8.dp)
                                )
                            }

                            // Enhanced keyboard bar with function keys and modifiers
                            EnhancedKeyboardBar(
                                theme = theme,
                                layout = settings.keyboardLayout,
                                hapticEnabled = settings.hapticFeedback,
                                onKeyPress = { key ->
                                    viewModel.sendSpecialKey(key)
                                },
                                onTextInput = { text ->
                                    viewModel.sendInput(text)
                                },
                                onCtrlKey = { char ->
                                    viewModel.sendCtrlKey(char)
                                },
                                onFunctionKey = { num ->
                                    viewModel.sendFunctionKey(num)
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
                                color = theme.red,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { viewModel.connect(connectionId) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = theme.accent
                                )
                            ) {
                                Text("Retry", color = theme.background)
                            }
                        }
                    }

                    else -> {
                        Text(
                            text = stringResource(R.string.terminal_disconnected),
                            modifier = Modifier.align(Alignment.Center),
                            color = theme.foreground
                        )
                    }
                }
            }
        }

        // Settings sheet
        if (uiState.showSettingsSheet) {
            TerminalSettingsSheet(
                settings = settings,
                onSettingsChange = { newSettings ->
                    viewModel.updateSettings(newSettings)
                },
                onDismiss = { viewModel.hideSettings() }
            )
        }
    }
}

/**
 * Handles terminal gestures and translates them to actions.
 */
private fun handleTerminalGesture(
    gesture: TerminalGesture,
    cellDimensions: com.terminox.presentation.terminal.components.CellDimensions,
    currentFontSize: Float,
    onFontSizeChange: (Float) -> Unit,
    onScrollChange: (Float) -> Unit,
    onSelectionChange: (TextSelection?) -> Unit,
    onOpenDrawer: () -> Unit,
    terminalState: com.terminox.protocol.terminal.TerminalState
) {
    when (gesture) {
        is TerminalGesture.PinchZoom -> {
            val newSize = currentFontSize + gesture.scaleFactor
            onFontSizeChange(newSize)
        }

        is TerminalGesture.TwoFingerScroll -> {
            onScrollChange(-gesture.deltaY)
        }

        is TerminalGesture.DoubleTap -> {
            // Select word at position
            val (col, row) = screenPositionToCell(
                gesture.position,
                cellDimensions.width,
                cellDimensions.height
            )
            val wordSelection = selectWordAt(terminalState, row, col)
            onSelectionChange(wordSelection)
        }

        is TerminalGesture.LongPress -> {
            // Start selection at position
            val (col, row) = screenPositionToCell(
                gesture.position,
                cellDimensions.width,
                cellDimensions.height
            )
            onSelectionChange(TextSelection(row, col, row, col))
        }

        is TerminalGesture.SelectionDrag -> {
            // Update selection end position
            val (startCol, startRow) = screenPositionToCell(
                gesture.start,
                cellDimensions.width,
                cellDimensions.height
            )
            val (endCol, endRow) = screenPositionToCell(
                gesture.end,
                cellDimensions.width,
                cellDimensions.height
            )
            onSelectionChange(TextSelection(startRow, startCol, endRow, endCol))
        }

        is TerminalGesture.SwipeFromEdge -> {
            if (gesture.edge == com.terminox.presentation.terminal.components.Edge.LEFT && gesture.progress > 0.5f) {
                onOpenDrawer()
            }
        }

        else -> {}
    }
}

/**
 * Handles clipboard actions.
 */
private fun handleClipboardAction(
    action: ClipboardAction,
    selection: TextSelection?,
    terminalState: com.terminox.protocol.terminal.TerminalState,
    clipboardHelper: TerminalClipboardHelper,
    onPaste: (String) -> Unit,
    onSelectAll: (TextSelection) -> Unit,
    onSelectionCleared: () -> Unit
) {
    when (action) {
        ClipboardAction.COPY -> {
            selection?.let { sel ->
                val text = extractSelectedText(terminalState, sel)
                if (text.isNotEmpty()) {
                    clipboardHelper.copyToClipboard(text)
                }
            }
            onSelectionCleared()
        }

        ClipboardAction.PASTE -> {
            clipboardHelper.getFromClipboard()?.let { text ->
                onPaste(text)
            }
        }

        ClipboardAction.SELECT_ALL -> {
            val rows = terminalState.lines.size
            val cols = if (rows > 0) terminalState.columns else 0
            onSelectAll(TextSelection(0, 0, rows - 1, cols))
        }
    }
}

/**
 * Selects a word at the given position.
 */
private fun selectWordAt(
    terminalState: com.terminox.protocol.terminal.TerminalState,
    row: Int,
    col: Int
): TextSelection? {
    if (row < 0 || row >= terminalState.lines.size) return null

    val line = terminalState.lines[row]
    if (col < 0 || col >= line.cells.size) return null

    // Find word boundaries
    var startCol = col
    var endCol = col

    // Move start to beginning of word
    while (startCol > 0 && !line.cells[startCol - 1].character.isWhitespace()) {
        startCol--
    }

    // Move end to end of word
    while (endCol < line.cells.size - 1 && !line.cells[endCol + 1].character.isWhitespace()) {
        endCol++
    }

    return TextSelection(row, startCol, row, endCol + 1)
}

/**
 * Extracts text from the terminal based on selection.
 */
private fun extractSelectedText(
    terminalState: com.terminox.protocol.terminal.TerminalState,
    selection: TextSelection
): String {
    val normalized = selection.normalize()
    val builder = StringBuilder()

    for (row in normalized.startRow..normalized.endRow) {
        if (row < 0 || row >= terminalState.lines.size) continue

        val line = terminalState.lines[row]
        val startCol = if (row == normalized.startRow) normalized.startColumn else 0
        val endCol = if (row == normalized.endRow) normalized.endColumn else line.cells.size

        for (col in startCol until endCol) {
            if (col >= 0 && col < line.cells.size) {
                builder.append(line.cells[col].character)
            }
        }

        if (row < normalized.endRow) {
            builder.append('\n')
        }
    }

    return builder.toString().trimEnd()
}
