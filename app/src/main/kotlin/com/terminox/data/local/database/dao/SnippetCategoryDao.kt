package com.terminox.data.local.database.dao

import androidx.room.*
import com.terminox.data.local.database.entity.SnippetCategoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SnippetCategoryDao {

    @Query("SELECT * FROM snippet_categories ORDER BY `order` ASC, name ASC")
    fun getAllCategories(): Flow<List<SnippetCategoryEntity>>

    @Query("SELECT * FROM snippet_categories WHERE parentId IS NULL ORDER BY `order` ASC, name ASC")
    fun getRootCategories(): Flow<List<SnippetCategoryEntity>>

    @Query("SELECT * FROM snippet_categories WHERE parentId = :parentId ORDER BY `order` ASC, name ASC")
    fun getChildCategories(parentId: String): Flow<List<SnippetCategoryEntity>>

    @Query("SELECT * FROM snippet_categories WHERE id = :id")
    fun getCategoryById(id: String): Flow<SnippetCategoryEntity?>

    @Query("SELECT * FROM snippet_categories WHERE id = :id")
    suspend fun getCategory(id: String): SnippetCategoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(category: SnippetCategoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(categories: List<SnippetCategoryEntity>)

    @Update
    suspend fun update(category: SnippetCategoryEntity)

    @Query("DELETE FROM snippet_categories WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM snippet_categories")
    suspend fun deleteAll()

    @Query("SELECT * FROM snippet_categories ORDER BY `order` ASC, name ASC")
    suspend fun getAllCategoriesList(): List<SnippetCategoryEntity>
}
