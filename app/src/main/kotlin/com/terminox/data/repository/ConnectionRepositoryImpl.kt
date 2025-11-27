package com.terminox.data.repository

import com.terminox.data.local.database.dao.ConnectionDao
import com.terminox.data.mapper.toDomain
import com.terminox.data.mapper.toEntity
import com.terminox.domain.model.Connection
import com.terminox.domain.repository.ConnectionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectionRepositoryImpl @Inject constructor(
    private val connectionDao: ConnectionDao
) : ConnectionRepository {

    override fun getAllConnections(): Flow<List<Connection>> {
        return connectionDao.getAllConnections().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getConnectionById(id: String): Flow<Connection?> {
        return connectionDao.getConnectionById(id).map { it?.toDomain() }
    }

    override suspend fun getConnection(id: String): Connection? {
        return connectionDao.getConnection(id)?.toDomain()
    }

    override suspend fun saveConnection(connection: Connection): Result<Unit> {
        return try {
            connectionDao.insert(connection.toEntity())
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateConnection(connection: Connection): Result<Unit> {
        return try {
            connectionDao.update(connection.toEntity())
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteConnection(id: String): Result<Unit> {
        return try {
            connectionDao.delete(id)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateLastConnected(id: String, timestamp: Long): Result<Unit> {
        return try {
            connectionDao.updateLastConnected(id, timestamp)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
