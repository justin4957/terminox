package com.terminox.presentation.snippets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.terminox.domain.model.Snippet
import com.terminox.domain.model.SnippetCategory

/**
 * Main snippets screen showing list of saved command snippets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnippetsScreen(
    snippets: List<Snippet>,
    categories: List<SnippetCategory>,
    selectedCategory: SnippetCategory? = null,
    onSnippetClick: (Snippet) -> Unit,
    onSnippetFavorite: (Snippet) -> Unit,
    onSnippetEdit: (Snippet) -> Unit,
    onSnippetDelete: (Snippet) -> Unit,
    onCreateSnippet: () -> Unit,
    onCategorySelect: (SnippetCategory?) -> Unit,
    onImportExport: () -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var showCategoryDialog by remember { mutableStateOf(false) }

    val filteredSnippets = remember(snippets, searchQuery, selectedCategory) {
        snippets.filter { snippet ->
            val matchesSearch = searchQuery.isEmpty() ||
                    snippet.name.contains(searchQuery, ignoreCase = true) ||
                    snippet.command.contains(searchQuery, ignoreCase = true) ||
                    snippet.tags.any { it.contains(searchQuery, ignoreCase = true) }

            val matchesCategory = selectedCategory == null || snippet.categoryId == selectedCategory.id

            matchesSearch && matchesCategory
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Command Snippets") },
                actions = {
                    IconButton(onClick = { showCategoryDialog = true }) {
                        Icon(Icons.Default.Folder, "Categories")
                    }
                    IconButton(onClick = onImportExport) {
                        Icon(Icons.Default.Upload, "Import/Export")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreateSnippet) {
                Icon(Icons.Default.Add, "New Snippet")
            }
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                placeholder = { Text("Search snippets...") },
                leadingIcon = {
                    Icon(Icons.Default.Search, null)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, "Clear")
                        }
                    }
                },
                singleLine = true
            )

            // Category filter chip
            if (selectedCategory != null) {
                FilterChip(
                    selected = true,
                    onClick = { onCategorySelect(null) },
                    label = { Text(selectedCategory.name) },
                    trailingIcon = {
                        Icon(Icons.Default.Close, "Clear category", Modifier.size(16.dp))
                    },
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // Snippets list
            if (filteredSnippets.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Code,
                            null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        Text(
                            if (snippets.isEmpty()) "No snippets yet" else "No matching snippets",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        TextButton(onClick = if (snippets.isEmpty()) onCreateSnippet else { { searchQuery = "" } }) {
                            Text(if (snippets.isEmpty()) "Create your first snippet" else "Clear search")
                        }
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredSnippets, key = { it.id }) { snippet ->
                        SnippetItem(
                            snippet = snippet,
                            onClick = { onSnippetClick(snippet) },
                            onFavorite = { onSnippetFavorite(snippet) },
                            onEdit = { onSnippetEdit(snippet) },
                            onDelete = { onSnippetDelete(snippet) }
                        )
                    }
                }
            }
        }

        // Category selection dialog
        if (showCategoryDialog) {
            CategorySelectionDialog(
                categories = categories,
                selectedCategory = selectedCategory,
                onSelect = {
                    onCategorySelect(it)
                    showCategoryDialog = false
                },
                onDismiss = { showCategoryDialog = false }
            )
        }
    }
}

/**
 * Individual snippet item in the list.
 */
@Composable
private fun SnippetItem(
    snippet: Snippet,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.Code,
                        null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        snippet.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Favorite icon
                IconButton(onClick = onFavorite) {
                    Icon(
                        if (snippet.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                        "Toggle favorite",
                        tint = if (snippet.isFavorite) Color(0xFFFFD700) else MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Command preview
            Text(
                snippet.getPreview(),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (snippet.description != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    snippet.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Footer row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (snippet.hasVariables()) {
                        AssistChip(
                            onClick = {},
                            label = { Text("${snippet.variables.size} vars", style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = {
                                Icon(Icons.Default.Settings, null, Modifier.size(16.dp))
                            }
                        )
                    }
                    if (snippet.useCount > 0) {
                        AssistChip(
                            onClick = {},
                            label = { Text("Used ${snippet.useCount}x", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Row {
                    IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Edit, "Edit", Modifier.size(18.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Delete, "Delete", Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

/**
 * Dialog for selecting a category filter.
 */
@Composable
private fun CategorySelectionDialog(
    categories: List<SnippetCategory>,
    selectedCategory: SnippetCategory?,
    onSelect: (SnippetCategory?) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filter by Category") },
        text = {
            LazyColumn {
                item {
                    ListItem(
                        headlineContent = { Text("All Snippets") },
                        leadingContent = {
                            RadioButton(
                                selected = selectedCategory == null,
                                onClick = { onSelect(null) }
                            )
                        },
                        modifier = Modifier.clickable { onSelect(null) }
                    )
                }
                items(categories) { category ->
                    ListItem(
                        headlineContent = { Text(category.name) },
                        supportingContent = category.description?.let { { Text(it) } },
                        leadingContent = {
                            RadioButton(
                                selected = selectedCategory?.id == category.id,
                                onClick = { onSelect(category) }
                            )
                        },
                        modifier = Modifier.clickable { onSelect(category) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}
