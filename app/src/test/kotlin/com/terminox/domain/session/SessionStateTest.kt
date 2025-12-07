package com.terminox.domain.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for SessionState sealed class.
 */
class SessionStateTest {

    @Test
    fun `Initializing state is not terminal`() {
        val state = SessionState.Initializing
        assertFalse(state.isTerminal())
    }

    @Test
    fun `Initializing state is not active`() {
        val state = SessionState.Initializing
        assertFalse(state.isActive())
    }

    @Test
    fun `Connecting state is not terminal`() {
        val state = SessionState.Connecting
        assertFalse(state.isTerminal())
    }

    @Test
    fun `Connecting state is not active`() {
        val state = SessionState.Connecting
        assertFalse(state.isActive())
    }

    @Test
    fun `AwaitingAuthentication state is not terminal`() {
        val state = SessionState.AwaitingAuthentication()
        assertFalse(state.isTerminal())
    }

    @Test
    fun `AwaitingAuthentication state is not active`() {
        val state = SessionState.AwaitingAuthentication()
        assertFalse(state.isActive())
    }

    @Test
    fun `Authenticating state is not terminal`() {
        val state = SessionState.Authenticating
        assertFalse(state.isTerminal())
    }

    @Test
    fun `Connected state is not terminal`() {
        val state = SessionState.Connected
        assertFalse(state.isTerminal())
    }

    @Test
    fun `Connected state is active`() {
        val state = SessionState.Connected
        assertTrue(state.isActive())
    }

    @Test
    fun `Disconnecting state is not terminal`() {
        val state = SessionState.Disconnecting
        assertFalse(state.isTerminal())
    }

    @Test
    fun `Disconnected state is terminal`() {
        val state = SessionState.Disconnected()
        assertTrue(state.isTerminal())
    }

    @Test
    fun `Disconnected state is not active`() {
        val state = SessionState.Disconnected()
        assertFalse(state.isActive())
    }

    @Test
    fun `Disconnected state preserves reason`() {
        val state = SessionState.Disconnected("Connection timeout")
        assertTrue(state.isTerminal())
    }

    @Test
    fun `Non-recoverable error state is terminal`() {
        val state = SessionState.Error(
            message = "Connection refused",
            cause = null,
            recoverable = false
        )
        assertTrue(state.isTerminal())
    }

    @Test
    fun `Recoverable error state is not terminal`() {
        val state = SessionState.Error(
            message = "Authentication failed",
            cause = null,
            recoverable = true
        )
        assertFalse(state.isTerminal())
    }

    @Test
    fun `Error state is never active`() {
        val recoverableError = SessionState.Error(
            message = "Auth failed",
            recoverable = true
        )
        val nonRecoverableError = SessionState.Error(
            message = "Connection refused",
            recoverable = false
        )
        assertFalse(recoverableError.isActive())
        assertFalse(nonRecoverableError.isActive())
    }
}

/**
 * Unit tests for SessionOutput sealed class.
 */
class SessionOutputTest {

    @Test
    fun `Data output equals with same bytes`() {
        val data1 = SessionOutput.Data(byteArrayOf(1, 2, 3))
        val data2 = SessionOutput.Data(byteArrayOf(1, 2, 3))
        assertTrue(data1 == data2)
    }

    @Test
    fun `Data output not equals with different bytes`() {
        val data1 = SessionOutput.Data(byteArrayOf(1, 2, 3))
        val data2 = SessionOutput.Data(byteArrayOf(4, 5, 6))
        assertFalse(data1 == data2)
    }

    @Test
    fun `Data output hashCode consistent for same bytes`() {
        val data1 = SessionOutput.Data(byteArrayOf(1, 2, 3))
        val data2 = SessionOutput.Data(byteArrayOf(1, 2, 3))
        assertTrue(data1.hashCode() == data2.hashCode())
    }

    @Test
    fun `Error output contains message`() {
        val error = SessionOutput.Error("Connection lost", RuntimeException("Network error"))
        assertTrue(error.message == "Connection lost")
        assertTrue(error.cause is RuntimeException)
    }

    @Test
    fun `Disconnected output contains reason`() {
        val disconnected = SessionOutput.Disconnected("User requested")
        assertTrue(disconnected.reason == "User requested")
    }

    @Test
    fun `Disconnected output allows null reason`() {
        val disconnected = SessionOutput.Disconnected()
        assertTrue(disconnected.reason == null)
    }
}

/**
 * Unit tests for AuthenticationMethod sealed class.
 */
class AuthenticationMethodTest {

    @Test
    fun `Password method is singleton`() {
        val method1 = AuthenticationMethod.Password
        val method2 = AuthenticationMethod.Password
        assertTrue(method1 === method2)
    }

    @Test
    fun `PublicKey method with keyId`() {
        val method = AuthenticationMethod.PublicKey("my-key-id")
        assertTrue(method.keyId == "my-key-id")
    }

    @Test
    fun `PublicKey method without keyId`() {
        val method = AuthenticationMethod.PublicKey()
        assertTrue(method.keyId == null)
    }

    @Test
    fun `KeyboardInteractive method is singleton`() {
        val method1 = AuthenticationMethod.KeyboardInteractive
        val method2 = AuthenticationMethod.KeyboardInteractive
        assertTrue(method1 === method2)
    }

    @Test
    fun `Agent method is singleton`() {
        val method1 = AuthenticationMethod.Agent
        val method2 = AuthenticationMethod.Agent
        assertTrue(method1 === method2)
    }
}

/**
 * Unit tests for AuthenticationResult sealed class.
 */
class AuthenticationResultTest {

    @Test
    fun `Success result isSuccess returns true`() {
        val result = AuthenticationResult.Success
        assertTrue(result.isSuccess())
    }

    @Test
    fun `Failure result isSuccess returns false`() {
        val result = AuthenticationResult.Failure("Wrong password")
        assertFalse(result.isSuccess())
    }

    @Test
    fun `Failure result preserves details`() {
        val cause = IllegalArgumentException("Invalid key format")
        val result = AuthenticationResult.Failure(
            reason = "Key authentication failed",
            cause = cause,
            retriesRemaining = 2
        )
        assertTrue(result.reason == "Key authentication failed")
        assertTrue(result.cause === cause)
        assertTrue(result.retriesRemaining == 2)
    }

    @Test
    fun `PromptRequired result isSuccess returns false`() {
        val result = AuthenticationResult.PromptRequired(
            prompts = listOf(
                AuthenticationPrompt("Enter code:", echo = true)
            )
        )
        assertFalse(result.isSuccess())
    }

    @Test
    fun `Cancelled result isSuccess returns false`() {
        val result = AuthenticationResult.Cancelled
        assertFalse(result.isSuccess())
    }
}

/**
 * Unit tests for TerminalDisplayState.
 */
class TerminalDisplayStateTest {

    @Test
    fun `Default display state has expected values`() {
        val state = TerminalDisplayState()
        assertTrue(state.lines.isEmpty())
        assertTrue(state.cursorRow == 0)
        assertTrue(state.cursorColumn == 0)
        assertTrue(state.cursorVisible)
        assertTrue(state.columns == 80)
        assertTrue(state.rows == 24)
        assertTrue(state.scrollbackSize == 0)
        assertTrue(state.title == null)
    }

    @Test
    fun `Display state with custom values`() {
        val lines = listOf(
            DisplayLine(listOf(DisplayCell('A'), DisplayCell('B')))
        )
        val state = TerminalDisplayState(
            lines = lines,
            cursorRow = 5,
            cursorColumn = 10,
            cursorVisible = false,
            columns = 120,
            rows = 40,
            scrollbackSize = 100,
            title = "bash"
        )
        assertTrue(state.lines.size == 1)
        assertTrue(state.cursorRow == 5)
        assertTrue(state.cursorColumn == 10)
        assertFalse(state.cursorVisible)
        assertTrue(state.columns == 120)
        assertTrue(state.rows == 40)
        assertTrue(state.scrollbackSize == 100)
        assertTrue(state.title == "bash")
    }
}

/**
 * Unit tests for DisplayCell.
 */
class DisplayCellTest {

    @Test
    fun `Default cell has expected values`() {
        val cell = DisplayCell()
        assertTrue(cell.character == ' ')
        assertTrue(cell.foreground == DisplayCell.DEFAULT_FOREGROUND)
        assertTrue(cell.background == DisplayCell.DEFAULT_BACKGROUND)
        assertFalse(cell.bold)
        assertFalse(cell.italic)
        assertFalse(cell.underline)
        assertFalse(cell.strikethrough)
        assertFalse(cell.inverse)
        assertFalse(cell.blink)
    }

    @Test
    fun `EMPTY cell matches default cell`() {
        val empty = DisplayCell.EMPTY
        val default = DisplayCell()
        assertTrue(empty == default)
    }

    @Test
    fun `Cell with all attributes set`() {
        val cell = DisplayCell(
            character = 'X',
            foreground = 0xFFFF0000.toInt(),
            background = 0xFF0000FF.toInt(),
            bold = true,
            italic = true,
            underline = true,
            strikethrough = true,
            inverse = true,
            blink = true
        )
        assertTrue(cell.character == 'X')
        assertTrue(cell.foreground == 0xFFFF0000.toInt())
        assertTrue(cell.background == 0xFF0000FF.toInt())
        assertTrue(cell.bold)
        assertTrue(cell.italic)
        assertTrue(cell.underline)
        assertTrue(cell.strikethrough)
        assertTrue(cell.inverse)
        assertTrue(cell.blink)
    }
}

/**
 * Unit tests for DisplayLine.
 */
class DisplayLineTest {

    @Test
    fun `Line with cells`() {
        val cells = listOf(
            DisplayCell('H'),
            DisplayCell('i'),
            DisplayCell('!')
        )
        val line = DisplayLine(cells)
        assertTrue(line.cells.size == 3)
        assertFalse(line.wrapped)
    }

    @Test
    fun `Wrapped line`() {
        val cells = listOf(DisplayCell('A'))
        val line = DisplayLine(cells, wrapped = true)
        assertTrue(line.wrapped)
    }
}
