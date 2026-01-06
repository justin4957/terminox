package com.terminox.data.repository

import com.terminox.data.local.database.dao.SnippetCategoryDao
import com.terminox.data.local.database.dao.SnippetDao
import com.terminox.data.mapper.toDomain
import com.terminox.data.mapper.toEntity
import com.terminox.domain.model.Snippet
import com.terminox.domain.model.SnippetCategory
import com.terminox.domain.model.SnippetExport
import com.terminox.domain.repository.ImportResult
import com.terminox.domain.repository.SnippetRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SnippetRepositoryImpl @Inject constructor(
    private val snippetDao: SnippetDao,
    private val categoryDao: SnippetCategoryDao
) : SnippetRepository {

    // Snippet operations
    override fun getAllSnippets(): Flow<List<Snippet>> {
        return snippetDao.getAllSnippets().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getSnippetsByCategory(categoryId: String?): Flow<List<Snippet>> {
        return snippetDao.getSnippetsByCategory(categoryId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getFavoriteSnippets(): Flow<List<Snippet>> {
        return snippetDao.getFavoriteSnippets().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getSnippetById(id: String): Flow<Snippet?> {
        return snippetDao.getSnippetById(id).map { it?.toDomain() }
    }

    override suspend fun getSnippet(id: String): Snippet? {
        return snippetDao.getSnippet(id)?.toDomain()
    }

    override suspend fun searchSnippets(query: String): Flow<List<Snippet>> {
        return snippetDao.searchSnippets(query).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun saveSnippet(snippet: Snippet): Result<Unit> {
        return try {
            snippetDao.insert(snippet.toEntity())
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateSnippet(snippet: Snippet): Result<Unit> {
        return try {
            snippetDao.update(snippet.toEntity())
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteSnippet(id: String): Result<Unit> {
        return try {
            snippetDao.delete(id)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun incrementUseCount(id: String): Result<Unit> {
        return try {
            snippetDao.incrementUseCount(id, System.currentTimeMillis())
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun toggleFavorite(id: String, isFavorite: Boolean): Result<Unit> {
        return try {
            snippetDao.updateFavorite(id, isFavorite)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getSnippetCount(): Int {
        return snippetDao.getSnippetCount()
    }

    // Category operations
    override fun getAllCategories(): Flow<List<SnippetCategory>> {
        return categoryDao.getAllCategories().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getRootCategories(): Flow<List<SnippetCategory>> {
        return categoryDao.getRootCategories().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getChildCategories(parentId: String): Flow<List<SnippetCategory>> {
        return categoryDao.getChildCategories(parentId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getCategoryById(id: String): Flow<SnippetCategory?> {
        return categoryDao.getCategoryById(id).map { it?.toDomain() }
    }

    override suspend fun getCategory(id: String): SnippetCategory? {
        return categoryDao.getCategory(id)?.toDomain()
    }

    override suspend fun saveCategory(category: SnippetCategory): Result<Unit> {
        return try {
            categoryDao.insert(category.toEntity())
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateCategory(category: SnippetCategory): Result<Unit> {
        return try {
            categoryDao.update(category.toEntity())
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteCategory(id: String): Result<Unit> {
        return try {
            categoryDao.delete(id)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Import/Export operations
    override suspend fun exportSnippets(snippetIds: List<String>?): Result<SnippetExport> {
        return try {
            val allSnippets = snippetDao.getAllSnippetsList().map { it.toDomain() }
            val snippets = if (snippetIds != null) {
                allSnippets.filter { it.id in snippetIds }
            } else {
                allSnippets
            }

            val allCategories = categoryDao.getAllCategoriesList().map { it.toDomain() }
            val usedCategoryIds = snippets.mapNotNull { it.categoryId }.toSet()
            val categories = allCategories.filter { it.id in usedCategoryIds }

            val export = SnippetExport(
                snippets = snippets,
                categories = categories
            )

            Result.success(export)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun exportAllSnippets(): Result<SnippetExport> {
        return try {
            val snippets = snippetDao.getAllSnippetsList().map { it.toDomain() }
            val categories = categoryDao.getAllCategoriesList().map { it.toDomain() }

            val export = SnippetExport(
                snippets = snippets,
                categories = categories
            )

            Result.success(export)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun importSnippets(
        export: SnippetExport,
        replaceExisting: Boolean
    ): Result<ImportResult> {
        return try {
            val errors = mutableListOf<String>()
            var snippetsImported = 0
            var categoriesImported = 0
            var snippetsSkipped = 0

            if (replaceExisting) {
                snippetDao.deleteAll()
                categoryDao.deleteAll()
            }

            // Import categories first
            for (category in export.categories) {
                try {
                    categoryDao.insert(category.toEntity())
                    categoriesImported++
                } catch (e: Exception) {
                    if (replaceExisting) {
                        errors.add("Failed to import category '${category.name}': ${e.message}")
                    } else {
                        // Category might already exist, skip
                    }
                }
            }

            // Import snippets
            for (snippet in export.snippets) {
                try {
                    if (!replaceExisting) {
                        // Check if snippet already exists
                        val existing = snippetDao.getSnippet(snippet.id)
                        if (existing != null) {
                            snippetsSkipped++
                            continue
                        }
                    }
                    snippetDao.insert(snippet.toEntity())
                    snippetsImported++
                } catch (e: Exception) {
                    errors.add("Failed to import snippet '${snippet.name}': ${e.message}")
                }
            }

            val result = ImportResult(
                snippetsImported = snippetsImported,
                categoriesImported = categoriesImported,
                snippetsSkipped = snippetsSkipped,
                errors = errors
            )

            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
