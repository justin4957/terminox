package com.terminox.domain.repository

import com.terminox.domain.model.Connection
import kotlinx.coroutines.flow.Flow

interface ConnectionRepository {
    fun getAllConnections(): Flow<List<Connection>>
    fun getConnectionById(id: String): Flow<Connection?>
    suspend fun getConnection(id: String): Connection?
    suspend fun saveConnection(connection: Connection): Result<Unit>
    suspend fun updateConnection(connection: Connection): Result<Unit>
    suspend fun deleteConnection(id: String): Result<Unit>
    suspend fun updateLastConnected(id: String, timestamp: Long): Result<Unit>
}
