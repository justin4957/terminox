package com.terminox.security

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.security.KeyStore
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for EncryptedScrollbackBuffer core functionality.
 * Tests the encryption/decryption logic, retention policies, and secure wiping.
 */
class EncryptedScrollbackBufferTest {

    private lateinit var mockSecretKey: SecretKey
    private lateinit var mockKeyStore: KeyStore
    private lateinit var buffer: EncryptedScrollbackBuffer

    @Before
    fun setup() {
        // Create a real AES key for testing (no Keystore dependency)
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        mockSecretKey = keyGen.generateKey()

        mockKeyStore = mockk(relaxed = true)
        every { mockKeyStore.containsAlias(any()) } returns false

        buffer = EncryptedScrollbackBuffer(
            sessionKey = mockSecretKey,
            keyAlias = "test-key",
            retentionPolicy = RetentionPolicy.DEFAULT,
            keyStore = mockKeyStore
        )
    }

    @After
    fun teardown() {
        unmockkAll()
        if (!buffer.isDestroyed()) {
            buffer.destroy()
        }
    }

    @Test
    fun `addLine encrypts and stores line correctly`() {
        // Act
        buffer.addLine("Hello, World!")

        // Assert
        assertEquals(1, buffer.size)
    }

    @Test
    fun `getLines decrypts and returns correct content`() {
        // Arrange
        buffer.addLine("Line 1")
        buffer.addLine("Line 2")
        buffer.addLine("Line 3")

        // Act
        val lines = buffer.getLines(0, 3)

        // Assert
        assertEquals(3, lines.size)
        assertEquals("Line 1", lines[0])
        assertEquals("Line 2", lines[1])
        assertEquals("Line 3", lines[2])
    }

    @Test
    fun `getLines with partial range returns correct subset`() {
        // Arrange
        buffer.addLine("Line 1")
        buffer.addLine("Line 2")
        buffer.addLine("Line 3")
        buffer.addLine("Line 4")
        buffer.addLine("Line 5")

        // Act
        val lines = buffer.getLines(1, 2)

        // Assert
        assertEquals(2, lines.size)
        assertEquals("Line 2", lines[0])
        assertEquals("Line 3", lines[1])
    }

    @Test
    fun `getLines with out of bounds start returns empty list`() {
        // Arrange
        buffer.addLine("Line 1")
        buffer.addLine("Line 2")

        // Act
        val lines = buffer.getLines(10, 5)

        // Assert
        assertEquals(0, lines.size)
    }

    @Test
    fun `getLines with out of bounds count returns available lines`() {
        // Arrange
        buffer.addLine("Line 1")
        buffer.addLine("Line 2")

        // Act
        val lines = buffer.getLines(0, 10)

        // Assert
        assertEquals(2, lines.size)
    }

    @Test
    fun `getAllLines returns all decrypted content`() {
        // Arrange
        val testLines = listOf("Alpha", "Beta", "Gamma", "Delta")
        testLines.forEach { buffer.addLine(it) }

        // Act
        val allLines = buffer.getAllLines()

        // Assert
        assertEquals(testLines, allLines)
    }

    @Test
    fun `clear removes all lines`() {
        // Arrange
        buffer.addLine("Line 1")
        buffer.addLine("Line 2")
        assertEquals(2, buffer.size)

        // Act
        buffer.clear()

        // Assert
        assertEquals(0, buffer.size)
    }

    @Test
    fun `destroy marks buffer as destroyed`() {
        // Arrange
        assertFalse(buffer.isDestroyed())

        // Act
        buffer.destroy()

        // Assert
        assertTrue(buffer.isDestroyed())
    }

    @Test
    fun `addLine on destroyed buffer throws IllegalStateException`() {
        // Arrange
        buffer.destroy()

        // Assert
        assertFailsWith<IllegalStateException> {
            buffer.addLine("test")
        }
    }

    @Test
    fun `getLines on destroyed buffer throws IllegalStateException`() {
        // Arrange
        buffer.destroy()

        // Assert
        assertFailsWith<IllegalStateException> {
            buffer.getLines(0, 1)
        }
    }

    @Test
    fun `getAllLines on destroyed buffer throws IllegalStateException`() {
        // Arrange
        buffer.destroy()

        // Assert
        assertFailsWith<IllegalStateException> {
            buffer.getAllLines()
        }
    }

    @Test
    fun `clear on destroyed buffer throws IllegalStateException`() {
        // Arrange
        buffer.destroy()

        // Assert
        assertFailsWith<IllegalStateException> {
            buffer.clear()
        }
    }

    @Test
    fun `retention policy enforces max lines`() {
        // Arrange
        val smallPolicy = RetentionPolicy(maxLines = 5)
        val smallBuffer = EncryptedScrollbackBuffer(
            sessionKey = mockSecretKey,
            keyAlias = "small-test",
            retentionPolicy = smallPolicy,
            keyStore = mockKeyStore
        )

        // Act - add more lines than max
        for (i in 1..10) {
            smallBuffer.addLine("Line $i")
        }

        // Assert - only last 5 lines should remain
        assertEquals(5, smallBuffer.size)
        val lines = smallBuffer.getAllLines()
        assertEquals("Line 6", lines[0])
        assertEquals("Line 10", lines[4])

        smallBuffer.destroy()
    }

    @Test
    fun `handles unicode content correctly`() {
        // Arrange
        val unicodeLines = listOf(
            "Hello 世界",
            "🚀 Rocket launch",
            "Ñoño español",
            "日本語テスト",
            "Émoji: 👍"
        )

        // Act
        unicodeLines.forEach { buffer.addLine(it) }
        val retrieved = buffer.getAllLines()

        // Assert
        assertEquals(unicodeLines, retrieved)
    }

    @Test
    fun `handles empty lines correctly`() {
        // Arrange & Act
        buffer.addLine("")
        buffer.addLine("Not empty")
        buffer.addLine("")

        // Assert
        val lines = buffer.getAllLines()
        assertEquals(3, lines.size)
        assertEquals("", lines[0])
        assertEquals("Not empty", lines[1])
        assertEquals("", lines[2])
    }

    @Test
    fun `handles very long lines correctly`() {
        // Arrange
        val longLine = "A".repeat(10000)

        // Act
        buffer.addLine(longLine)

        // Assert
        val retrieved = buffer.getAllLines()
        assertEquals(1, retrieved.size)
        assertEquals(longLine, retrieved[0])
    }

    @Test
    fun `handles special characters correctly`() {
        // Arrange
        val specialChars = listOf(
            "Tab\there",
            "Newline\nembedded",
            "Quote\"test",
            "Backslash\\path",
            "Null\u0000char"
        )

        // Act
        specialChars.forEach { buffer.addLine(it) }
        val retrieved = buffer.getAllLines()

        // Assert
        assertEquals(specialChars, retrieved)
    }

    @Test
    fun `size property returns correct count`() {
        // Assert initial state
        assertEquals(0, buffer.size)

        // Add lines and verify
        buffer.addLine("Line 1")
        assertEquals(1, buffer.size)

        buffer.addLine("Line 2")
        buffer.addLine("Line 3")
        assertEquals(3, buffer.size)

        // Clear and verify
        buffer.clear()
        assertEquals(0, buffer.size)
    }

    @Test
    fun `enforceRetention can be called manually`() {
        // Arrange
        val policy = RetentionPolicy(maxLines = 3)
        val smallBuffer = EncryptedScrollbackBuffer(
            sessionKey = mockSecretKey,
            keyAlias = "enforce-test",
            retentionPolicy = policy,
            keyStore = mockKeyStore
        )

        // Lines added within policy
        smallBuffer.addLine("Line 1")
        smallBuffer.addLine("Line 2")
        smallBuffer.addLine("Line 3")

        // Act - manual enforcement shouldn't change anything
        smallBuffer.enforceRetention()

        // Assert
        assertEquals(3, smallBuffer.size)

        smallBuffer.destroy()
    }

    @Test
    fun `multiple independent buffers work correctly`() {
        // Arrange
        val buffer2 = EncryptedScrollbackBuffer(
            sessionKey = mockSecretKey,
            keyAlias = "test-key-2",
            retentionPolicy = RetentionPolicy.DEFAULT,
            keyStore = mockKeyStore
        )

        // Act
        buffer.addLine("Buffer 1 line")
        buffer2.addLine("Buffer 2 line")

        // Assert
        assertEquals(1, buffer.size)
        assertEquals(1, buffer2.size)
        assertEquals("Buffer 1 line", buffer.getAllLines()[0])
        assertEquals("Buffer 2 line", buffer2.getAllLines()[0])

        buffer2.destroy()
    }
}

/**
 * Tests for RetentionPolicy configuration.
 */
class RetentionPolicyTest {

    @Test
    fun `default policy has expected values`() {
        val policy = RetentionPolicy.DEFAULT
        assertEquals(10000, policy.maxLines)
        assertEquals(3600L, policy.maxAgeSeconds)
        assertTrue(policy.wipeOnSessionClose)
        assertFalse(policy.wipeOnAppBackground)
    }

    @Test
    fun `secure policy has stricter values`() {
        val policy = RetentionPolicy.SECURE
        assertEquals(1000, policy.maxLines)
        assertEquals(900L, policy.maxAgeSeconds)
        assertTrue(policy.wipeOnSessionClose)
        assertTrue(policy.wipeOnAppBackground)
    }

    @Test
    fun `maximum security policy has most restrictive values`() {
        val policy = RetentionPolicy.MAXIMUM_SECURITY
        assertEquals(100, policy.maxLines)
        assertEquals(300L, policy.maxAgeSeconds)
        assertTrue(policy.wipeOnSessionClose)
        assertTrue(policy.wipeOnAppBackground)
    }

    @Test
    fun `custom policy can be created`() {
        val policy = RetentionPolicy(
            maxLines = 500,
            maxAgeSeconds = 1800L,
            wipeOnSessionClose = false,
            wipeOnAppBackground = true
        )

        assertEquals(500, policy.maxLines)
        assertEquals(1800L, policy.maxAgeSeconds)
        assertFalse(policy.wipeOnSessionClose)
        assertTrue(policy.wipeOnAppBackground)
    }
}

/**
 * Tests for secure memory wiping utilities.
 */
class SecureWipeTest {

    @Test
    fun `ByteArray secureWipe fills with zeros`() {
        // Arrange
        val data = byteArrayOf(1, 2, 3, 4, 5)

        // Act
        data.secureWipe()

        // Assert
        assertTrue(data.all { it == 0.toByte() })
    }

    @Test
    fun `CharArray secureWipe fills with null chars`() {
        // Arrange
        val data = charArrayOf('a', 'b', 'c', 'd')

        // Act
        data.secureWipe()

        // Assert
        assertTrue(data.all { it == '\u0000' })
    }

    @Test
    fun `empty ByteArray secureWipe does not throw`() {
        // Arrange
        val data = byteArrayOf()

        // Act & Assert - should not throw
        data.secureWipe()
        assertEquals(0, data.size)
    }

    @Test
    fun `empty CharArray secureWipe does not throw`() {
        // Arrange
        val data = charArrayOf()

        // Act & Assert - should not throw
        data.secureWipe()
        assertEquals(0, data.size)
    }
}

/**
 * Tests for EncryptedLine data class.
 */
class EncryptedLineTest {

    @Test
    fun `secureWipe clears encrypted content and IV`() {
        // Arrange
        val content = byteArrayOf(1, 2, 3, 4, 5)
        val iv = byteArrayOf(10, 20, 30)
        val line = EncryptedLine(content, iv, 0)

        // Act
        line.secureWipe()

        // Assert
        assertTrue(content.all { it == 0.toByte() })
        assertTrue(iv.all { it == 0.toByte() })
    }

    @Test
    fun `equals works correctly for identical content`() {
        // Arrange
        val line1 = EncryptedLine(byteArrayOf(1, 2, 3), byteArrayOf(4, 5), 0)
        val line2 = EncryptedLine(byteArrayOf(1, 2, 3), byteArrayOf(4, 5), 0)

        // Assert
        assertEquals(line1, line2)
    }

    @Test
    fun `hashCode is consistent for identical content`() {
        // Arrange
        val line1 = EncryptedLine(byteArrayOf(1, 2, 3), byteArrayOf(4, 5), 0)
        val line2 = EncryptedLine(byteArrayOf(1, 2, 3), byteArrayOf(4, 5), 0)

        // Assert
        assertEquals(line1.hashCode(), line2.hashCode())
    }
}
