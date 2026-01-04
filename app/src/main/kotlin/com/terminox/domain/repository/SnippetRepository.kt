package com.terminox.domain.repository

import com.terminox.domain.model.Snippet
import com.terminox.domain.model.SnippetCategory
import com.terminox.domain.model.SnippetExport
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for managing command snippets.
 */
interface SnippetRepository {

    // Snippet operations
    fun getAllSnippets(): Flow<List<Snippet>>
    fun getSnippetsByCategory(categoryId: String?): Flow<List<Snippet>>
    fun getFavoriteSnippets(): Flow<List<Snippet>>
    fun getSnippetById(id: String): Flow<Snippet?>
    suspend fun getSnippet(id: String): Snippet?
    suspend fun searchSnippets(query: String): Flow<List<Snippet>>
    suspend fun saveSnippet(snippet: Snippet): Result<Unit>
    suspend fun updateSnippet(snippet: Snippet): Result<Unit>
    suspend fun deleteSnippet(id: String): Result<Unit>
    suspend fun incrementUseCount(id: String): Result<Unit>
    suspend fun toggleFavorite(id: String, isFavorite: Boolean): Result<Unit>
    suspend fun getSnippetCount(): Int

    // Category operations
    fun getAllCategories(): Flow<List<SnippetCategory>>
    fun getRootCategories(): Flow<List<SnippetCategory>>
    fun getChildCategories(parentId: String): Flow<List<SnippetCategory>>
    fun getCategoryById(id: String): Flow<SnippetCategory?>
    suspend fun getCategory(id: String): SnippetCategory?
    suspend fun saveCategory(category: SnippetCategory): Result<Unit>
    suspend fun updateCategory(category: SnippetCategory): Result<Unit>
    suspend fun deleteCategory(id: String): Result<Unit>

    // Import/Export operations
    suspend fun exportSnippets(snippetIds: List<String>? = null): Result<SnippetExport>
    suspend fun exportAllSnippets(): Result<SnippetExport>
    suspend fun importSnippets(export: SnippetExport, replaceExisting: Boolean = false): Result<ImportResult>
}

/**
 * Result of importing snippets.
 */
data class ImportResult(
    val snippetsImported: Int,
    val categoriesImported: Int,
    val snippetsSkipped: Int,
    val errors: List<String> = emptyList()
)
