package com.terminox.data.local.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Database entity for storing snippet categories/folders.
 */
@Entity(tableName = "snippet_categories")
data class SnippetCategoryEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val description: String?,
    val icon: String?,
    val parentId: String?,
    val order: Int
)
