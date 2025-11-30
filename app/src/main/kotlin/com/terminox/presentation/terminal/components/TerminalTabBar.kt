package com.terminox.presentation.terminal.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.terminox.domain.model.TerminalTheme

/**
 * Represents a terminal tab.
 */
data class TerminalTab(
    val id: String,
    val title: String,
    val isActive: Boolean = false,
    val hasActivity: Boolean = false,
    val workingDirectory: String? = null
)

/**
 * Tab bar for terminal tabs with swipe gestures and activity indicators.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TerminalTabBar(
    tabs: List<TerminalTab>,
    activeTabId: String?,
    theme: TerminalTheme,
    onTabSelected: (String) -> Unit,
    onTabClose: (String) -> Unit,
    onNewTab: () -> Unit,
    onTabReorder: ((fromIndex: Int, toIndex: Int) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Surface(
        color = theme.toolbarBackground,
        tonalElevation = 2.dp,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .horizontalScroll(scrollState),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { index, tab ->
                TabItem(
                    tab = tab,
                    isActive = tab.id == activeTabId,
                    theme = theme,
                    onClick = { onTabSelected(tab.id) },
                    onClose = { onTabClose(tab.id) },
                    onLongClick = { /* TODO: Show tab options menu */ },
                    modifier = Modifier.padding(start = if (index == 0) 4.dp else 0.dp)
                )
            }

            // New tab button
            IconButton(
                onClick = onNewTab,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "New Tab",
                    tint = theme.tabForeground,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabItem(
    tab: TerminalTab,
    isActive: Boolean,
    theme: TerminalTheme,
    onClick: () -> Unit,
    onClose: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isActive) theme.tabActiveBackground else theme.tabBackground,
        label = "tabBackground"
    )
    val textColor by animateColorAsState(
        targetValue = if (isActive) theme.tabActiveForeground else theme.tabForeground,
        label = "tabText"
    )
    val elevation by animateFloatAsState(
        targetValue = if (isActive) 2f else 0f,
        label = "tabElevation"
    )

    Surface(
        color = backgroundColor,
        shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
        shadowElevation = elevation.dp,
        modifier = modifier
            .padding(horizontal = 2.dp)
            .graphicsLayer { this.shadowElevation = elevation }
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .widthIn(min = 80.dp, max = 200.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Activity indicator
            if (tab.hasActivity && !isActive) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(theme.accent)
                )
            }

            // Tab title
            Text(
                text = tab.title,
                color = textColor,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )

            // Close button
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(16.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close Tab",
                    tint = textColor.copy(alpha = 0.7f),
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

/**
 * Compact tab indicator for when many tabs are open.
 * Shows dots or minimal indicators instead of full tabs.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CompactTabIndicator(
    tabCount: Int,
    activeIndex: Int,
    theme: TerminalTheme,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(tabCount) { index ->
            val isActive = index == activeIndex
            val color by animateColorAsState(
                targetValue = if (isActive) theme.accent else theme.tabForeground.copy(alpha = 0.4f),
                label = "dotColor"
            )
            val size by animateFloatAsState(
                targetValue = if (isActive) 8f else 6f,
                label = "dotSize"
            )

            Box(
                modifier = Modifier
                    .size(size.dp)
                    .clip(CircleShape)
                    .background(color)
                    .combinedClickable(onClick = { onTabSelected(index) })
            )
        }
    }
}

/**
 * Tab strip with swipe gesture support for quick tab switching.
 */
@Composable
fun SwipeableTabStrip(
    tabs: List<TerminalTab>,
    activeTabIndex: Int,
    theme: TerminalTheme,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var swipeOffset by remember { mutableFloatStateOf(0f) }
    val swipeThreshold = 100f

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (swipeOffset > swipeThreshold && activeTabIndex > 0) {
                            onSwipeLeft()
                        } else if (swipeOffset < -swipeThreshold && activeTabIndex < tabs.size - 1) {
                            onSwipeRight()
                        }
                        swipeOffset = 0f
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        swipeOffset += dragAmount
                    }
                )
            }
    ) {
        content()

        // Tab position indicator
        if (tabs.size > 1) {
            CompactTabIndicator(
                tabCount = tabs.size,
                activeIndex = activeTabIndex,
                theme = theme,
                onTabSelected = { /* handled by parent */ },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 2.dp)
            )
        }
    }
}
