package com.terminox.data.mapper

import com.terminox.data.local.database.entity.SnippetCategoryEntity
import com.terminox.data.local.database.entity.SnippetEntity
import com.terminox.domain.model.Snippet
import com.terminox.domain.model.SnippetCategory
import com.terminox.domain.model.SnippetVariable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

/**
 * Convert SnippetEntity to domain Snippet.
 */
fun SnippetEntity.toDomain(): Snippet {
    return Snippet(
        id = id,
        name = name,
        command = command,
        description = description,
        categoryId = categoryId,
        variables = try {
            json.decodeFromString<List<SnippetVariable>>(variablesJson)
        } catch (e: Exception) {
            emptyList()
        },
        createdAt = createdAt,
        lastUsedAt = lastUsedAt,
        useCount = useCount,
        isFavorite = isFavorite,
        tags = try {
            json.decodeFromString<List<String>>(tagsJson)
        } catch (e: Exception) {
            emptyList()
        }
    )
}

/**
 * Convert domain Snippet to SnippetEntity.
 */
fun Snippet.toEntity(): SnippetEntity {
    return SnippetEntity(
        id = id,
        name = name,
        command = command,
        description = description,
        categoryId = categoryId,
        variablesJson = json.encodeToString(variables),
        createdAt = createdAt,
        lastUsedAt = lastUsedAt,
        useCount = useCount,
        isFavorite = isFavorite,
        tagsJson = json.encodeToString(tags)
    )
}

/**
 * Convert SnippetCategoryEntity to domain SnippetCategory.
 */
fun SnippetCategoryEntity.toDomain(): SnippetCategory {
    return SnippetCategory(
        id = id,
        name = name,
        description = description,
        icon = icon,
        parentId = parentId,
        order = order
    )
}

/**
 * Convert domain SnippetCategory to SnippetCategoryEntity.
 */
fun SnippetCategory.toEntity(): SnippetCategoryEntity {
    return SnippetCategoryEntity(
        id = id,
        name = name,
        description = description,
        icon = icon,
        parentId = parentId,
        order = order
    )
}
