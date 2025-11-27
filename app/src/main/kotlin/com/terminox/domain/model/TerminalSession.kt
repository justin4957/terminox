package com.terminox.domain.model

data class TerminalSession(
    val sessionId: String,
    val connection: Connection,
    val state: SessionState,
    val startedAt: Long = System.currentTimeMillis(),
    val terminalSize: TerminalSize = TerminalSize()
)

data class TerminalSize(
    val columns: Int = 80,
    val rows: Int = 24
)

enum class SessionState {
    CONNECTING,
    AUTHENTICATING,
    CONNECTED,
    DISCONNECTING,
    DISCONNECTED,
    ERROR
}
