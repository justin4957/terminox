package com.terminox.data.repository

import com.terminox.data.local.database.dao.ConnectionDao
import com.terminox.data.local.database.entity.ConnectionEntity
import com.terminox.domain.model.AuthMethod
import com.terminox.domain.model.Connection
import com.terminox.domain.model.ProtocolType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConnectionRepositoryImplTest {

    private lateinit var connectionDao: ConnectionDao
    private lateinit var repository: ConnectionRepositoryImpl

    @Before
    fun setup() {
        connectionDao = mockk(relaxed = true)
        repository = ConnectionRepositoryImpl(connectionDao)
    }

    @Test
    fun `getAllConnections returns mapped domain models`() = runTest {
        // Given
        val entities = listOf(
            createConnectionEntity("1", "Server 1"),
            createConnectionEntity("2", "Server 2")
        )
        every { connectionDao.getAllConnections() } returns flowOf(entities)

        // When
        val result = repository.getAllConnections().first()

        // Then
        assertEquals(2, result.size)
        assertEquals("Server 1", result[0].name)
        assertEquals("Server 2", result[1].name)
    }

    @Test
    fun `getConnection returns null when not found`() = runTest {
        // Given
        coEvery { connectionDao.getConnection("nonexistent") } returns null

        // When
        val result = repository.getConnection("nonexistent")

        // Then
        assertNull(result)
    }

    @Test
    fun `getConnection returns mapped connection when found`() = runTest {
        // Given
        val entity = createConnectionEntity("1", "Test Server")
        coEvery { connectionDao.getConnection("1") } returns entity

        // When
        val result = repository.getConnection("1")

        // Then
        assertNotNull(result)
        assertEquals("Test Server", result?.name)
        assertEquals("192.168.1.100", result?.host)
        assertEquals(22, result?.port)
        assertEquals("admin", result?.username)
        assertEquals(ProtocolType.SSH, result?.protocol)
    }

    @Test
    fun `saveConnection inserts entity and returns success`() = runTest {
        // Given
        val connection = createConnection("1", "New Server")
        coEvery { connectionDao.insert(any()) } returns Unit

        // When
        val result = repository.saveConnection(connection)

        // Then
        assertTrue(result.isSuccess)
        coVerify { connectionDao.insert(any()) }
    }

    @Test
    fun `saveConnection returns failure on exception`() = runTest {
        // Given
        val connection = createConnection("1", "New Server")
        coEvery { connectionDao.insert(any()) } throws RuntimeException("Database error")

        // When
        val result = repository.saveConnection(connection)

        // Then
        assertTrue(result.isFailure)
    }

    @Test
    fun `deleteConnection removes entity and returns success`() = runTest {
        // Given
        coEvery { connectionDao.delete("1") } returns Unit

        // When
        val result = repository.deleteConnection("1")

        // Then
        assertTrue(result.isSuccess)
        coVerify { connectionDao.delete("1") }
    }

    @Test
    fun `updateLastConnected updates timestamp`() = runTest {
        // Given
        val timestamp = System.currentTimeMillis()
        coEvery { connectionDao.updateLastConnected("1", timestamp) } returns Unit

        // When
        val result = repository.updateLastConnected("1", timestamp)

        // Then
        assertTrue(result.isSuccess)
        coVerify { connectionDao.updateLastConnected("1", timestamp) }
    }

    @Test
    fun `getConnectionById returns flow of mapped connection`() = runTest {
        // Given
        val entity = createConnectionEntity("1", "Test Server")
        every { connectionDao.getConnectionById("1") } returns flowOf(entity)

        // When
        val result = repository.getConnectionById("1").first()

        // Then
        assertNotNull(result)
        assertEquals("Test Server", result?.name)
    }

    @Test
    fun `connection with public key auth is mapped correctly`() = runTest {
        // Given
        val entity = createConnectionEntity("1", "Key Auth Server").copy(
            authMethod = "PUBLIC_KEY",
            keyId = "key-123"
        )
        coEvery { connectionDao.getConnection("1") } returns entity

        // When
        val result = repository.getConnection("1")

        // Then
        assertNotNull(result)
        assertTrue(result?.authMethod is AuthMethod.PublicKey)
        assertEquals("key-123", (result?.authMethod as AuthMethod.PublicKey).keyId)
    }

    private fun createConnectionEntity(
        id: String,
        name: String
    ) = ConnectionEntity(
        id = id,
        name = name,
        host = "192.168.1.100",
        port = 22,
        username = "admin",
        protocol = "SSH",
        authMethod = "PASSWORD",
        keyId = null,
        createdAt = System.currentTimeMillis(),
        lastConnectedAt = null
    )

    private fun createConnection(
        id: String,
        name: String
    ) = Connection(
        id = id,
        name = name,
        host = "192.168.1.100",
        port = 22,
        username = "admin",
        protocol = ProtocolType.SSH,
        authMethod = AuthMethod.Password,
        keyId = null,
        createdAt = System.currentTimeMillis(),
        lastConnectedAt = null
    )
}
