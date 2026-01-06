package com.terminox.data.local.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Database entity for storing command snippets.
 */
@Entity(tableName = "snippets")
data class SnippetEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val command: String,
    val description: String?,
    val categoryId: String?,
    val variablesJson: String, // JSON array of SnippetVariable
    val createdAt: Long,
    val lastUsedAt: Long?,
    val useCount: Int,
    val isFavorite: Boolean,
    val tagsJson: String // JSON array of strings
)
