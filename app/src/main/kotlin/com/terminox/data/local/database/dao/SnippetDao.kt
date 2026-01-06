package com.terminox.data.local.database.dao

import androidx.room.*
import com.terminox.data.local.database.entity.SnippetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SnippetDao {

    @Query("SELECT * FROM snippets ORDER BY isFavorite DESC, lastUsedAt DESC, name ASC")
    fun getAllSnippets(): Flow<List<SnippetEntity>>

    @Query("SELECT * FROM snippets WHERE categoryId = :categoryId ORDER BY isFavorite DESC, lastUsedAt DESC, name ASC")
    fun getSnippetsByCategory(categoryId: String?): Flow<List<SnippetEntity>>

    @Query("SELECT * FROM snippets WHERE isFavorite = 1 ORDER BY lastUsedAt DESC, name ASC")
    fun getFavoriteSnippets(): Flow<List<SnippetEntity>>

    @Query("SELECT * FROM snippets WHERE id = :id")
    fun getSnippetById(id: String): Flow<SnippetEntity?>

    @Query("SELECT * FROM snippets WHERE id = :id")
    suspend fun getSnippet(id: String): SnippetEntity?

    @Query("""
        SELECT * FROM snippets
        WHERE name LIKE '%' || :query || '%'
        OR command LIKE '%' || :query || '%'
        OR description LIKE '%' || :query || '%'
        OR tagsJson LIKE '%' || :query || '%'
        ORDER BY isFavorite DESC, lastUsedAt DESC, name ASC
    """)
    fun searchSnippets(query: String): Flow<List<SnippetEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(snippet: SnippetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(snippets: List<SnippetEntity>)

    @Update
    suspend fun update(snippet: SnippetEntity)

    @Query("DELETE FROM snippets WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM snippets")
    suspend fun deleteAll()

    @Query("UPDATE snippets SET lastUsedAt = :timestamp, useCount = useCount + 1 WHERE id = :id")
    suspend fun incrementUseCount(id: String, timestamp: Long)

    @Query("UPDATE snippets SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun updateFavorite(id: String, isFavorite: Boolean)

    @Query("SELECT COUNT(*) FROM snippets")
    suspend fun getSnippetCount(): Int

    @Query("SELECT * FROM snippets ORDER BY isFavorite DESC, lastUsedAt DESC, name ASC")
    suspend fun getAllSnippetsList(): List<SnippetEntity>
}
