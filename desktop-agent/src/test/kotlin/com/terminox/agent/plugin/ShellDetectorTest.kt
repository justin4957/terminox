package com.terminox.agent.plugin

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for ShellDetector.
 */
class ShellDetectorTest {

    @Test
    fun `platform detection returns correct platform`() {
        val detector = ShellDetector()
        val osName = System.getProperty("os.name").lowercase()

        when {
            osName.contains("linux") -> assertEquals(PtyPlatform.LINUX, detector.platform)
            osName.contains("mac") || osName.contains("darwin") -> assertEquals(PtyPlatform.MACOS, detector.platform)
            osName.contains("windows") -> assertEquals(PtyPlatform.WINDOWS, detector.platform)
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `detectShells finds shells on Unix`() {
        val detector = ShellDetector()
        val shells = detector.detectShells()

        assertTrue(shells.isNotEmpty(), "Should find at least one shell")

        // At minimum /bin/sh should exist
        val hasBasicShell = shells.any { shell ->
            shell.path.contains("/sh") || shell.path.contains("/bash")
        }
        assertTrue(hasBasicShell, "Should find sh or bash")
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `detectShells finds shells on Windows`() {
        val detector = ShellDetector()
        val shells = detector.detectShells()

        assertTrue(shells.isNotEmpty(), "Should find at least one shell")

        // At minimum cmd.exe should exist
        val hasCmdExe = shells.any { it.type == ShellType.CMD }
        assertTrue(hasCmdExe, "Should find cmd.exe")
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `getDefaultShell returns valid shell on Unix`() {
        val detector = ShellDetector()
        val defaultShell = detector.getDefaultShell()

        assertNotNull(defaultShell, "Should have a default shell")
        assertTrue(defaultShell.isDefault, "Should be marked as default")

        val file = java.io.File(defaultShell.path)
        assertTrue(file.exists(), "Shell file should exist")
        assertTrue(file.canExecute(), "Shell should be executable")
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `getDefaultShell returns valid shell on Windows`() {
        val detector = ShellDetector()
        val defaultShell = detector.getDefaultShell()

        assertNotNull(defaultShell, "Should have a default shell")
        assertTrue(defaultShell.isDefault, "Should be marked as default")

        // Should be PowerShell or cmd
        assertTrue(
            defaultShell.type in listOf(ShellType.PWSH, ShellType.POWERSHELL, ShellType.CMD),
            "Default Windows shell should be PowerShell or cmd"
        )
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `validateShell succeeds for existing shell`() {
        val detector = ShellDetector()

        // /bin/sh should always exist
        val result = detector.validateShell("/bin/sh")
        assertTrue(result.isSuccess, "Validation should succeed for /bin/sh")
    }

    @Test
    fun `validateShell fails for non-existent shell`() {
        val detector = ShellDetector()
        val result = detector.validateShell("/nonexistent/shell")
        assertTrue(result.isFailure, "Validation should fail for non-existent shell")
    }

    @Test
    fun `validateShell respects allowed shells list`() {
        val config = ShellConfig(allowedShells = listOf("/bin/sh"))
        val detector = ShellDetector(config)

        // /bin/sh should be allowed
        val shResult = detector.validateShell("/bin/sh")
        assertTrue(shResult.isSuccess, "/bin/sh should be allowed")

        // /bin/bash should not be allowed (if it exists and is different)
        val bashFile = java.io.File("/bin/bash")
        if (bashFile.exists() && bashFile.canonicalPath != java.io.File("/bin/sh").canonicalPath) {
            val bashResult = detector.validateShell("/bin/bash")
            assertTrue(bashResult.isFailure, "/bin/bash should not be allowed")
        }
    }

    @Test
    fun `getShellArgs returns configured arguments`() {
        val config = ShellConfig(
            shellArgs = mapOf(
                "/bin/bash" to listOf("--login", "--norc"),
                "bash" to listOf("-i")
            )
        )
        val detector = ShellDetector(config)

        // Full path should match
        val args = detector.getShellArgs("/bin/bash")
        assertEquals(listOf("--login", "--norc"), args)

        // Name-only should also match
        val nameArgs = detector.getShellArgs("bash")
        assertEquals(listOf("-i"), nameArgs)

        // Unknown shell should return empty
        val unknownArgs = detector.getShellArgs("/bin/unknown")
        assertTrue(unknownArgs.isEmpty())
    }

    @Test
    fun `getShellCommand builds correct command array`() {
        val config = ShellConfig(
            shellArgs = mapOf("/bin/bash" to listOf("--login")),
            loginShell = true
        )
        val detector = ShellDetector(config)

        val command = detector.getShellCommand("/bin/bash")
        assertEquals("/bin/bash", command[0])
        assertEquals("--login", command[1])
    }

    @Test
    fun `shell type detection is correct`() {
        val detector = ShellDetector()

        // Create shell infos and check type detection
        val shells = detector.detectShells()
        for (shell in shells) {
            val name = java.io.File(shell.path).name.lowercase().removeSuffix(".exe")
            when (name) {
                "bash" -> assertEquals(ShellType.BASH, shell.type)
                "zsh" -> assertEquals(ShellType.ZSH, shell.type)
                "fish" -> assertEquals(ShellType.FISH, shell.type)
                "sh", "dash" -> assertEquals(ShellType.SH, shell.type)
                "cmd" -> assertEquals(ShellType.CMD, shell.type)
                "powershell" -> assertEquals(ShellType.POWERSHELL, shell.type)
                "pwsh" -> assertEquals(ShellType.PWSH, shell.type)
            }
        }
    }

    @Test
    fun `shell capabilities are set based on type`() {
        val detector = ShellDetector()
        val shells = detector.detectShells()

        for (shell in shells) {
            when (shell.type) {
                ShellType.BASH, ShellType.ZSH, ShellType.FISH -> {
                    assertTrue(
                        ShellCapability.ANSI_COLORS in shell.capabilities,
                        "${shell.type} should support ANSI colors"
                    )
                    assertTrue(
                        ShellCapability.JOB_CONTROL in shell.capabilities,
                        "${shell.type} should support job control"
                    )
                }
                ShellType.POWERSHELL, ShellType.PWSH -> {
                    assertTrue(
                        ShellCapability.ANSI_COLORS in shell.capabilities,
                        "${shell.type} should support ANSI colors"
                    )
                    assertTrue(
                        ShellCapability.TAB_COMPLETION in shell.capabilities,
                        "${shell.type} should support tab completion"
                    )
                }
                ShellType.CMD -> {
                    assertTrue(
                        ShellCapability.ANSI_COLORS in shell.capabilities,
                        "CMD should support ANSI colors"
                    )
                }
                else -> {}
            }
        }
    }
}
