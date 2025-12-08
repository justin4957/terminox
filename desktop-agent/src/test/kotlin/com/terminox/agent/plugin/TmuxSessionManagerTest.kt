package com.terminox.agent.plugin

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.condition.EnabledIf
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for TmuxSessionManager.
 *
 * Note: Some tests require tmux to be installed on the system.
 */
class TmuxSessionManagerTest {

    private lateinit var manager: TmuxSessionManager

    @BeforeEach
    fun setUp() {
        manager = TmuxSessionManager()
    }

    @Test
    fun `backend type is TMUX`() {
        assertEquals(BackendType.TMUX, manager.type)
    }

    @Test
    fun `backend name is Tmux`() {
        assertEquals("Tmux", manager.name)
    }

    @Test
    fun `capabilities support attach and persistence`() {
        assertTrue(manager.capabilities.supportsAttach)
        assertTrue(manager.capabilities.supportsPersistence)
        assertTrue(manager.capabilities.supportsMultiplePanes)
        assertTrue(manager.capabilities.supportsSharing)
        assertTrue(manager.capabilities.supportsCopyMode)
    }

    @Test
    fun `isAvailable returns false before initialization`() {
        // Before initialize(), tmuxPath is null
        assertFalse(manager.isAvailable)
    }

    @Test
    @EnabledIf("tmuxInstalled")
    fun `initialize succeeds when tmux is installed`() = runBlocking {
        val result = manager.initialize()
        assertTrue(result.isSuccess)
        assertTrue(manager.isAvailable)
    }

    @Test
    @EnabledIf("tmuxInstalled")
    fun `listSessions returns list after initialization`() = runBlocking {
        manager.initialize()
        val sessions = manager.listSessions()
        // May be empty, but should not throw
        assertNotNull(sessions)
    }

    @Test
    fun `createSession fails when not initialized`() = runBlocking {
        val config = TerminalSessionConfig(
            shell = "/bin/bash",
            columns = 80,
            rows = 24
        )
        val result = manager.createSession(config)
        assertTrue(result.isFailure)
    }

    @Test
    fun `attachSession fails when not initialized`() = runBlocking {
        val config = TerminalSessionConfig(
            shell = "/bin/bash",
            columns = 80,
            rows = 24
        )
        val result = manager.attachSession("nonexistent", config)
        assertTrue(result.isFailure)
    }

    @Test
    fun `sessionExists returns false when not initialized`() = runBlocking {
        val exists = manager.sessionExists("test-session")
        assertFalse(exists)
    }

    @Test
    fun `shutdown completes without error`() = runBlocking {
        manager.shutdown()
        // Should not throw
    }

    @Test
    @EnabledIf("tmuxInstalled")
    fun `session name validation rejects invalid characters`() = runBlocking {
        manager.initialize()

        // Session names with colons should fail
        val config = TerminalSessionConfig(
            shell = "/bin/bash",
            sessionName = "invalid:name",
            columns = 80,
            rows = 24
        )
        val result = manager.createSession(config)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Invalid session name") == true)
    }

    @Test
    @EnabledIf("tmuxInstalled")
    fun `session name validation rejects periods`() = runBlocking {
        manager.initialize()

        val config = TerminalSessionConfig(
            shell = "/bin/bash",
            sessionName = "invalid.name",
            columns = 80,
            rows = 24
        )
        val result = manager.createSession(config)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Invalid session name") == true)
    }

    companion object {
        @JvmStatic
        fun tmuxInstalled(): Boolean {
            return try {
                val process = ProcessBuilder("which", "tmux")
                    .redirectErrorStream(true)
                    .start()
                process.waitFor() == 0
            } catch (e: Exception) {
                false
            }
        }
    }
}
