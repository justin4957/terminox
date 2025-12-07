package com.terminox.protocol.session

import com.terminox.domain.model.Connection
import com.terminox.domain.model.ProtocolType
import com.terminox.domain.model.TerminalSize
import com.terminox.domain.session.AuthenticationMethod
import com.terminox.domain.session.AuthenticationResult
import com.terminox.domain.session.DisplayCell
import com.terminox.domain.session.DisplayLine
import com.terminox.domain.session.SessionAuthenticator
import com.terminox.domain.session.SessionOutput
import com.terminox.domain.session.SessionState
import com.terminox.domain.session.TerminalDisplayState
import com.terminox.domain.session.TerminalSessionPort
import com.terminox.protocol.TerminalOutput
import com.terminox.protocol.ssh.SshProtocolAdapter
import com.terminox.protocol.terminal.TerminalEmulator
import com.terminox.protocol.terminal.TerminalLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.security.PrivateKey
import java.security.PublicKey

/**
 * SSH implementation of the TerminalSessionPort interface.
 * Wraps the SshProtocolAdapter and TerminalEmulator to provide
 * a unified session interface.
 */
class SshTerminalSession(
    override val id: String,
    override val connection: Connection,
    private val sshAdapter: SshProtocolAdapter,
    private val emulator: TerminalEmulator = TerminalEmulator()
) : TerminalSessionPort, SessionAuthenticator {

    override val protocolType: ProtocolType = ProtocolType.SSH

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var outputCollectionJob: Job? = null
    private var isAuthenticatedFlag = false

    private val _state = MutableStateFlow<SessionState>(SessionState.Connecting)
    override val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _terminalState = MutableStateFlow(createTerminalDisplayState())
    override val terminalState: StateFlow<TerminalDisplayState> = _terminalState.asStateFlow()

    override val output: Flow<SessionOutput> = sshAdapter.outputFlow(id).map { output ->
        when (output) {
            is TerminalOutput.Data -> SessionOutput.Data(output.bytes)
            is TerminalOutput.Error -> SessionOutput.Error(output.message, output.cause)
            is TerminalOutput.Disconnected -> SessionOutput.Disconnected()
        }
    }

    init {
        // Observe terminal emulator state changes
        scope.launch {
            emulator.state.collect { _ ->
                _terminalState.value = createTerminalDisplayState()
            }
        }
    }

    // TerminalSessionPort implementation

    override suspend fun write(data: ByteArray): Result<Unit> {
        return sshAdapter.sendInput(id, data)
    }

    override suspend fun resize(columns: Int, rows: Int): Result<Unit> {
        emulator.resize(columns, rows)
        return sshAdapter.resize(id, TerminalSize(columns, rows))
    }

    override suspend fun disconnect(): Result<Unit> {
        _state.value = SessionState.Disconnecting
        outputCollectionJob?.cancel()
        val result = sshAdapter.disconnect(id)
        _state.value = SessionState.Disconnected()
        scope.cancel()
        return result
    }

    override suspend fun isConnected(): Boolean {
        return sshAdapter.isConnected(id)
    }

    override fun getTerminalSize(): TerminalSize {
        return TerminalSize(emulator.columns, emulator.rows)
    }

    override fun processOutput(data: ByteArray) {
        emulator.processInput(data)
    }

    // SessionAuthenticator implementation

    override suspend fun authenticateWithPassword(password: String): AuthenticationResult {
        _state.value = SessionState.Authenticating
        val result = sshAdapter.authenticateWithPassword(id, password)
        return if (result.isSuccess) {
            isAuthenticatedFlag = true
            _state.value = SessionState.Connected
            startOutputCollection()
            AuthenticationResult.Success
        } else {
            val error = result.exceptionOrNull()
            _state.value = SessionState.Error(
                message = error?.message ?: "Authentication failed",
                cause = error,
                recoverable = true
            )
            AuthenticationResult.Failure(
                reason = error?.message ?: "Authentication failed",
                cause = error
            )
        }
    }

    override suspend fun authenticateWithKey(
        privateKey: String,
        publicKey: String?,
        passphrase: String?
    ): AuthenticationResult {
        // This method takes string keys, but we need to convert to Java key objects
        // The actual key parsing should happen at a higher level
        return AuthenticationResult.Failure(
            reason = "Use authenticateWithKeyPair for pre-parsed keys",
            cause = null
        )
    }

    /**
     * Authenticates with pre-parsed key objects.
     * This is the preferred method when keys are already loaded.
     */
    suspend fun authenticateWithKeyPair(
        privateKey: PrivateKey,
        publicKey: PublicKey
    ): AuthenticationResult {
        _state.value = SessionState.Authenticating
        val result = sshAdapter.authenticateWithKey(id, privateKey, publicKey)
        return if (result.isSuccess) {
            isAuthenticatedFlag = true
            _state.value = SessionState.Connected
            startOutputCollection()
            AuthenticationResult.Success
        } else {
            val error = result.exceptionOrNull()
            _state.value = SessionState.Error(
                message = error?.message ?: "Key authentication failed",
                cause = error,
                recoverable = true
            )
            AuthenticationResult.Failure(
                reason = error?.message ?: "Key authentication failed",
                cause = error
            )
        }
    }

    override suspend fun authenticateWithAgent(): AuthenticationResult {
        return AuthenticationResult.Failure(
            reason = "Agent authentication not yet supported",
            cause = null
        )
    }

    override suspend fun getAvailableMethods(): List<AuthenticationMethod> {
        // SSH typically supports these methods
        return listOf(
            AuthenticationMethod.Password,
            AuthenticationMethod.PublicKey()
        )
    }

    override fun isAuthenticated(): Boolean = isAuthenticatedFlag

    override suspend fun cancelAuthentication() {
        // Cancel any pending authentication by disconnecting
        if (!isAuthenticatedFlag) {
            disconnect()
        }
    }

    /**
     * Updates the session state to awaiting authentication.
     * Called after connection is established but before auth.
     */
    fun setAwaitingAuthentication() {
        _state.value = SessionState.AwaitingAuthentication(
            methods = listOf(
                AuthenticationMethod.Password,
                AuthenticationMethod.PublicKey()
            )
        )
    }

    /**
     * Starts collecting output from the SSH adapter and processing it through the emulator.
     */
    private fun startOutputCollection() {
        outputCollectionJob = scope.launch {
            sshAdapter.outputFlow(id).collect { output ->
                when (output) {
                    is TerminalOutput.Data -> {
                        emulator.processInput(output.bytes)
                    }
                    is TerminalOutput.Error -> {
                        _state.value = SessionState.Error(
                            message = output.message,
                            cause = output.cause,
                            recoverable = false
                        )
                    }
                    is TerminalOutput.Disconnected -> {
                        _state.value = SessionState.Disconnected()
                    }
                }
            }
        }
    }

    /**
     * Creates a TerminalDisplayState from the current emulator state.
     */
    private fun createTerminalDisplayState(): TerminalDisplayState {
        val termState = emulator.state.value
        return TerminalDisplayState(
            lines = termState.lines.map { line -> convertLine(line) },
            cursorRow = termState.cursorRow,
            cursorColumn = termState.cursorColumn,
            cursorVisible = termState.cursorVisible,
            columns = termState.columns,
            rows = termState.rows,
            scrollbackSize = termState.scrollbackSize
        )
    }

    /**
     * Converts a protocol TerminalLine to a domain DisplayLine.
     */
    private fun convertLine(line: TerminalLine): DisplayLine {
        return DisplayLine(
            cells = line.cells.map { cell ->
                DisplayCell(
                    character = cell.character,
                    foreground = convertColor(cell.style.foreground, true),
                    background = convertColor(cell.style.background, false),
                    bold = cell.style.attributes.bold,
                    italic = cell.style.attributes.italic,
                    underline = cell.style.attributes.underline,
                    strikethrough = cell.style.attributes.strikethrough,
                    inverse = cell.style.attributes.inverse,
                    blink = cell.style.attributes.blink
                )
            },
            wrapped = line.wrapped
        )
    }

    /**
     * Converts a TerminalColor to an ARGB integer.
     */
    private fun convertColor(color: com.terminox.protocol.terminal.TerminalColor, isForeground: Boolean): Int {
        return when (color) {
            is com.terminox.protocol.terminal.TerminalColor.Default -> {
                if (isForeground) DisplayCell.DEFAULT_FOREGROUND else DisplayCell.DEFAULT_BACKGROUND
            }
            is com.terminox.protocol.terminal.TerminalColor.Ansi -> {
                ansiToArgb(color.code)
            }
            is com.terminox.protocol.terminal.TerminalColor.Indexed -> {
                indexedToArgb(color.index)
            }
            is com.terminox.protocol.terminal.TerminalColor.Rgb -> {
                (0xFF shl 24) or (color.red shl 16) or (color.green shl 8) or color.blue
            }
        }
    }

    private fun ansiToArgb(code: Int): Int {
        val ansiColors = intArrayOf(
            0xFF000000.toInt(), // 0: Black
            0xFFCD0000.toInt(), // 1: Red
            0xFF00CD00.toInt(), // 2: Green
            0xFFCDCD00.toInt(), // 3: Yellow
            0xFF0000EE.toInt(), // 4: Blue
            0xFFCD00CD.toInt(), // 5: Magenta
            0xFF00CDCD.toInt(), // 6: Cyan
            0xFFE5E5E5.toInt(), // 7: White
            0xFF7F7F7F.toInt(), // 8: Bright Black
            0xFFFF0000.toInt(), // 9: Bright Red
            0xFF00FF00.toInt(), // 10: Bright Green
            0xFFFFFF00.toInt(), // 11: Bright Yellow
            0xFF5C5CFF.toInt(), // 12: Bright Blue
            0xFFFF00FF.toInt(), // 13: Bright Magenta
            0xFF00FFFF.toInt(), // 14: Bright Cyan
            0xFFFFFFFF.toInt()  // 15: Bright White
        )
        return if (code in ansiColors.indices) ansiColors[code] else DisplayCell.DEFAULT_FOREGROUND
    }

    private fun indexedToArgb(index: Int): Int {
        return when {
            index < 16 -> ansiToArgb(index)
            index < 232 -> {
                val i = index - 16
                val r = (i / 36) * 51
                val g = ((i / 6) % 6) * 51
                val b = (i % 6) * 51
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            else -> {
                val gray = (index - 232) * 10 + 8
                (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
            }
        }
    }
}
