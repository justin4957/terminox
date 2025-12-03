package com.terminox.presentation.security

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.terminox.domain.model.ConnectionEvent
import com.terminox.domain.model.ConnectionEventStats
import com.terminox.domain.model.ConnectionEventType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionHistoryScreen(
    onNavigateBack: () -> Unit,
    viewModel: ConnectionHistoryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connection History") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.showStatsDialog() }) {
                        Icon(Icons.Default.Info, contentDescription = "Statistics")
                    }
                    IconButton(onClick = { viewModel.exportEvents() }) {
                        Icon(Icons.Default.Share, contentDescription = "Export")
                    }
                    IconButton(onClick = { viewModel.showClearConfirmDialog() }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            FilterChipsRow(
                selectedFilter = uiState.selectedFilter,
                onFilterSelected = { viewModel.setFilter(it) }
            )

            if (uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (uiState.filteredEvents.isEmpty()) {
                EmptyState()
            } else {
                EventsList(events = uiState.filteredEvents)
            }
        }
    }

    if (uiState.showStatsDialog && uiState.statistics != null) {
        StatisticsDialog(
            statistics = uiState.statistics!!,
            onDismiss = { viewModel.hideStatsDialog() }
        )
    }

    if (uiState.showClearConfirmDialog) {
        ClearConfirmDialog(
            onDismiss = { viewModel.hideClearConfirmDialog() },
            onClearOld = { viewModel.clearOldEvents() },
            onClearAll = { viewModel.clearAllEvents() }
        )
    }

    if (uiState.showExportDialog && uiState.exportedJson != null) {
        ExportDialog(
            json = uiState.exportedJson!!,
            onDismiss = { viewModel.hideExportDialog() },
            onCopy = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Connection History", uiState.exportedJson)
                clipboard.setPrimaryClip(clip)
                viewModel.hideExportDialog()
            }
        )
    }
}

@Composable
private fun FilterChipsRow(
    selectedFilter: EventFilter,
    onFilterSelected: (EventFilter) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(EventFilter.entries) { filter ->
            FilterChip(
                selected = filter == selectedFilter,
                onClick = { onFilterSelected(filter) },
                label = { Text(filter.displayName) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    }
}

@Composable
private fun EventsList(events: List<ConnectionEvent>) {
    val groupedEvents = events.groupBy { event ->
        val date = Date(event.timestamp)
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(date)
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        groupedEvents.forEach { (dateString, dayEvents) ->
            item {
                DateHeader(dateString)
            }
            items(dayEvents, key = { it.id }) { event ->
                EventCard(event = event)
            }
        }
    }
}

@Composable
private fun DateHeader(dateString: String) {
    val displayDate = try {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dateString)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val yesterday = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(
            Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1))
        )
        when (dateString) {
            today -> "Today"
            yesterday -> "Yesterday"
            else -> SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(date!!)
        }
    } catch (e: Exception) {
        dateString
    }

    Text(
        text = displayDate,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun EventCard(event: ConnectionEvent) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (event.success) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EventIcon(event = event)

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = event.connectionName ?: event.host,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    EventTypeBadge(eventType = event.eventType, success = event.success)
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = buildEventDescription(event),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                event.errorMessage?.let { error ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = formatTime(event.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EventIcon(event: ConnectionEvent) {
    val (icon, tint) = when {
        !event.success -> Icons.Default.Close to MaterialTheme.colorScheme.error
        event.eventType == ConnectionEventType.HOST_KEY_CHANGED -> Icons.Default.Warning to Color(0xFFFF9800)
        else -> Icons.Default.Check to Color(0xFF4CAF50)
    }

    Box(
        modifier = Modifier
            .size(36.dp)
            .background(
                color = tint.copy(alpha = 0.1f),
                shape = MaterialTheme.shapes.small
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun EventTypeBadge(eventType: ConnectionEventType, success: Boolean) {
    val (text, color) = when (eventType) {
        ConnectionEventType.CONNECTION_SUCCESS -> "Connected" to Color(0xFF4CAF50)
        ConnectionEventType.CONNECTION_FAILED -> "Failed" to MaterialTheme.colorScheme.error
        ConnectionEventType.CONNECTION_ATTEMPT -> "Attempt" to Color(0xFF2196F3)
        ConnectionEventType.SESSION_END -> "Ended" to Color(0xFF9E9E9E)
        ConnectionEventType.HOST_KEY_CHANGED -> "Key Changed" to Color(0xFFFF9800)
        ConnectionEventType.HOST_KEY_VERIFIED -> "Verified" to Color(0xFF4CAF50)
        ConnectionEventType.KEY_USAGE -> "Key Used" to Color(0xFF9C27B0)
        ConnectionEventType.AUTHENTICATION_SUCCESS -> "Auth OK" to Color(0xFF4CAF50)
        ConnectionEventType.AUTHENTICATION_FAILED -> "Auth Failed" to MaterialTheme.colorScheme.error
        ConnectionEventType.AUTHENTICATION_ATTEMPT -> "Auth" to Color(0xFF2196F3)
        ConnectionEventType.SESSION_START -> "Started" to Color(0xFF4CAF50)
        ConnectionEventType.DISCONNECTED -> "Disconnected" to Color(0xFF9E9E9E)
    }

    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .background(
                color = color.copy(alpha = 0.1f),
                shape = MaterialTheme.shapes.extraSmall
            )
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

private fun buildEventDescription(event: ConnectionEvent): String {
    val parts = mutableListOf<String>()

    if (event.connectionName != null && event.host != event.connectionName) {
        parts.add(event.host)
    }
    parts.add(":${event.port}")

    event.username?.let { parts.add("as $it") }
    event.authMethod?.let { parts.add("($it)") }

    event.durationMs?.let { duration ->
        val seconds = duration / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val durationStr = when {
            hours > 0 -> "${hours}h ${minutes % 60}m"
            minutes > 0 -> "${minutes}m ${seconds % 60}s"
            else -> "${seconds}s"
        }
        parts.add("Duration: $durationStr")
    }

    event.keyFingerprint?.let {
        val shortFingerprint = if (it.length > 20) it.take(20) + "..." else it
        parts.add("Key: $shortFingerprint")
    }

    return parts.joinToString(" ")
}

private fun formatTime(timestamp: Long): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No events found",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Connection events will appear here",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun StatisticsDialog(
    statistics: ConnectionEventStats,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connection Statistics") },
        text = {
            Column {
                StatRow("Total Connections", statistics.totalConnections.toString())
                StatRow("Successful", statistics.successfulConnections.toString())
                StatRow("Failed", statistics.failedConnections.toString())
                StatRow("Unique Hosts", statistics.uniqueHosts.toString())

                if (statistics.totalSessionDurationMs > 0) {
                    val hours = statistics.totalSessionDurationMs / (1000 * 60 * 60)
                    val minutes = (statistics.totalSessionDurationMs / (1000 * 60)) % 60
                    StatRow("Total Time", "${hours}h ${minutes}m")
                }

                if (statistics.authMethodBreakdown.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        "Auth Methods",
                        style = MaterialTheme.typography.titleSmall
                    )
                    statistics.authMethodBreakdown.forEach { (method, count) ->
                        StatRow(method, count.toString())
                    }
                }

                if (statistics.mostUsedKeys.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        "Most Used Keys",
                        style = MaterialTheme.typography.titleSmall
                    )
                    statistics.mostUsedKeys.take(3).forEach { (fingerprint, count) ->
                        val shortFp = if (fingerprint.length > 16) {
                            fingerprint.take(16) + "..."
                        } else fingerprint
                        StatRow(shortFp, "$count uses")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ClearConfirmDialog(
    onDismiss: () -> Unit,
    onClearOld: () -> Unit,
    onClearAll: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clear History") },
        text = {
            Text("Choose what to clear from your connection history.")
        },
        confirmButton = {
            TextButton(onClick = onClearAll) {
                Text("Clear All", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
                TextButton(onClick = onClearOld) {
                    Text("Clear Old (30+ days)")
                }
            }
        }
    )
}

@Composable
private fun ExportDialog(
    json: String,
    onDismiss: () -> Unit,
    onCopy: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export History") },
        text = {
            Column {
                Text(
                    "Your connection history has been prepared for export.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "${json.lines().size} lines of data ready to copy.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCopy) {
                Text("Copy to Clipboard")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
