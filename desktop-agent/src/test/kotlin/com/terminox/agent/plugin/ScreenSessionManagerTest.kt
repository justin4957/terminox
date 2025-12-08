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
 * Unit tests for ScreenSessionManager.
 *
 * Note: Some tests require GNU Screen to be installed on the system.
 */
class ScreenSessionManagerTest {

    private lateinit var manager: ScreenSessionManager

    @BeforeEach
    fun setUp() {
        manager = ScreenSessionManager()
    }

    @Test
    fun `backend type is SCREEN`() {
        assertEquals(BackendType.SCREEN, manager.type)
    }

    @Test
    fun `backend name is GNU Screen`() {
        assertEquals("GNU Screen", manager.name)
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
        // Before initialize(), screenPath is null
        assertFalse(manager.isAvailable)
    }

    @Test
    @EnabledIf("screenInstalled")
    fun `initialize succeeds when screen is installed`() = runBlocking {
        val result = manager.initialize()
        assertTrue(result.isSuccess)
        assertTrue(manager.isAvailable)
    }

    @Test
    @EnabledIf("screenInstalled")
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
    @EnabledIf("screenInstalled")
    fun `session name validation accepts valid names`() = runBlocking {
        manager.initialize()

        // Valid session name with alphanumeric and dashes
        val config = TerminalSessionConfig(
            shell = "/bin/bash",
            sessionName = "valid-session-name",
            columns = 80,
            rows = 24
        )
        // This will fail because we can't actually create the session in tests easily,
        // but the validation should pass
        val result = manager.createSession(config)
        // The failure should NOT be about invalid session name
        if (result.isFailure) {
            assertFalse(result.exceptionOrNull()?.message?.contains("Invalid session name") == true)
        }
    }

    @Test
    @EnabledIf("screenInstalled")
    fun `parse screen list output correctly`() = runBlocking {
        manager.initialize()
        // Just verify listSessions doesn't throw and returns a list
        val sessions = manager.listSessions()
        // Verify the structure is correct (may be empty)
        for (session in sessions) {
            assertNotNull(session.id)
            assertNotNull(session.name)
        }
    }

    companion object {
        @JvmStatic
        fun screenInstalled(): Boolean {
            return try {
                val process = ProcessBuilder("which", "screen")
                    .redirectErrorStream(true)
                    .start()
                process.waitFor() == 0
            } catch (e: Exception) {
                false
            }
        }
    }
}
