package com.terminox.presentation.connections

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.terminox.domain.model.SecurityLevel

/**
 * A selector component for choosing a security level.
 */
@Composable
fun SecurityLevelSelector(
    selectedLevel: SecurityLevel,
    onLevelSelected: (SecurityLevel) -> Unit,
    recommendedLevel: SecurityLevel? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup()
    ) {
        Text(
            text = "Security Level",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        SecurityLevel.entries.forEach { level ->
            SecurityLevelOption(
                level = level,
                isSelected = selectedLevel == level,
                isRecommended = recommendedLevel == level,
                onClick = { onLevelSelected(level) },
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun SecurityLevelOption(
    level: SecurityLevel,
    isSelected: Boolean,
    isRecommended: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor by animateColorAsState(
        targetValue = when {
            isSelected -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surface
        },
        label = "backgroundColor"
    )

    val borderColor by animateColorAsState(
        targetValue = when {
            isSelected -> MaterialTheme.colorScheme.primary
            isRecommended -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        },
        label = "borderColor"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .selectable(
                selected = isSelected,
                onClick = onClick,
                role = Role.RadioButton
            ),
        shape = RoundedCornerShape(12.dp),
        color = backgroundColor,
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = borderColor
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Security level icon
            Icon(
                imageVector = level.icon(),
                contentDescription = level.iconDescription(),
                tint = level.iconColor(),
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Text content
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = level.displayName(),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                    )

                    if (isRecommended) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Text(
                                text = "Recommended",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Text(
                    text = level.description(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Radio button indicator
            RadioButton(
                selected = isSelected,
                onClick = null,
                colors = RadioButtonDefaults.colors(
                    selectedColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

/**
 * Compact version of the security level selector for limited space.
 */
@Composable
fun SecurityLevelChips(
    selectedLevel: SecurityLevel,
    onLevelSelected: (SecurityLevel) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SecurityLevel.entries.forEach { level ->
            FilterChip(
                selected = selectedLevel == level,
                onClick = { onLevelSelected(level) },
                label = { Text(level.shortName()) },
                leadingIcon = if (selectedLevel == level) {
                    {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize)
                        )
                    }
                } else null,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Security level indicator badge.
 */
@Composable
fun SecurityLevelBadge(
    level: SecurityLevel,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = level.badgeColor().copy(alpha = 0.15f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = level.icon(),
                contentDescription = null,
                tint = level.badgeColor(),
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = level.shortName(),
                style = MaterialTheme.typography.labelSmall,
                color = level.badgeColor()
            )
        }
    }
}

// Extension functions for SecurityLevel UI properties
private fun SecurityLevel.icon(): ImageVector = when (this) {
    SecurityLevel.DEVELOPMENT -> Icons.Default.Code
    SecurityLevel.HOME_NETWORK -> Icons.Default.Home
    SecurityLevel.INTERNET -> Icons.Default.Public
    SecurityLevel.MAXIMUM -> Icons.Default.Shield
}

@Composable
private fun SecurityLevel.iconColor(): Color = when (this) {
    SecurityLevel.DEVELOPMENT -> Color(0xFF4CAF50) // Green
    SecurityLevel.HOME_NETWORK -> Color(0xFF2196F3) // Blue
    SecurityLevel.INTERNET -> Color(0xFFFF9800) // Orange
    SecurityLevel.MAXIMUM -> Color(0xFFF44336) // Red
}

@Composable
private fun SecurityLevel.badgeColor(): Color = when (this) {
    SecurityLevel.DEVELOPMENT -> Color(0xFF4CAF50)
    SecurityLevel.HOME_NETWORK -> Color(0xFF2196F3)
    SecurityLevel.INTERNET -> Color(0xFFFF9800)
    SecurityLevel.MAXIMUM -> Color(0xFFF44336)
}

private fun SecurityLevel.shortName(): String = when (this) {
    SecurityLevel.DEVELOPMENT -> "Dev"
    SecurityLevel.HOME_NETWORK -> "Home"
    SecurityLevel.INTERNET -> "Internet"
    SecurityLevel.MAXIMUM -> "Max"
}
