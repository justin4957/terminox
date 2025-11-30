package com.terminox.presentation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.terminox.domain.model.CursorStyle
import com.terminox.domain.model.TerminalSettings
import com.terminox.domain.model.TerminalTheme
import com.terminox.domain.model.TerminalThemes

/**
 * Bottom sheet for terminal settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalSettingsSheet(
    settings: TerminalSettings,
    onSettingsChange: (TerminalSettings) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1A2E),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = "Terminal Settings",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Theme Selection
            SettingsSection(title = "Theme") {
                ThemeSelector(
                    selectedTheme = settings.themeName,
                    onThemeSelected = { onSettingsChange(settings.copy(themeName = it)) }
                )
            }

            // Font Settings
            SettingsSection(title = "Font") {
                FontSizeSlider(
                    fontSize = settings.fontSize,
                    onFontSizeChange = { onSettingsChange(settings.copy(fontSize = it)) }
                )
            }

            // Cursor Settings
            SettingsSection(title = "Cursor") {
                CursorStyleSelector(
                    style = settings.cursorStyle,
                    onStyleChange = { onSettingsChange(settings.copy(cursorStyle = it)) }
                )

                SwitchSetting(
                    title = "Cursor Blink",
                    description = "Animate cursor blinking",
                    icon = Icons.Default.Animation,
                    checked = settings.cursorBlink,
                    onCheckedChange = { onSettingsChange(settings.copy(cursorBlink = it)) }
                )
            }

            // Display Settings
            SettingsSection(title = "Display") {
                SwitchSetting(
                    title = "Show Tab Bar",
                    description = "Show tabs when multiple shells are open",
                    icon = Icons.Default.Tab,
                    checked = settings.showTabBar,
                    onCheckedChange = { onSettingsChange(settings.copy(showTabBar = it)) }
                )

                SwitchSetting(
                    title = "Immersive Mode",
                    description = "Hide system bars for more terminal space",
                    icon = Icons.Default.Fullscreen,
                    checked = settings.immersiveMode,
                    onCheckedChange = { onSettingsChange(settings.copy(immersiveMode = it)) }
                )

                SwitchSetting(
                    title = "Keep Screen On",
                    description = "Prevent screen from turning off",
                    icon = Icons.Default.Brightness7,
                    checked = settings.keepScreenOn,
                    onCheckedChange = { onSettingsChange(settings.copy(keepScreenOn = it)) }
                )
            }

            // Input Settings
            SettingsSection(title = "Input") {
                SwitchSetting(
                    title = "Haptic Feedback",
                    description = "Vibrate on key press",
                    icon = Icons.Default.Vibration,
                    checked = settings.hapticFeedback,
                    onCheckedChange = { onSettingsChange(settings.copy(hapticFeedback = it)) }
                )

                SwitchSetting(
                    title = "Swipe Gestures",
                    description = "Use swipe for scrolling and tab switching",
                    icon = Icons.Default.Swipe,
                    checked = settings.swipeGestures,
                    onCheckedChange = { onSettingsChange(settings.copy(swipeGestures = it)) }
                )

                SwitchSetting(
                    title = "Double-tap to Select",
                    description = "Select word on double tap",
                    icon = Icons.Default.TouchApp,
                    checked = settings.doubleTapToSelect,
                    onCheckedChange = { onSettingsChange(settings.copy(doubleTapToSelect = it)) }
                )
            }

            // Bell Settings
            SettingsSection(title = "Bell") {
                SwitchSetting(
                    title = "Bell Sound",
                    description = "Play sound on terminal bell",
                    icon = Icons.AutoMirrored.Filled.VolumeUp,
                    checked = settings.bellSound,
                    onCheckedChange = { onSettingsChange(settings.copy(bellSound = it)) }
                )

                SwitchSetting(
                    title = "Bell Vibration",
                    description = "Vibrate on terminal bell",
                    icon = Icons.Default.Vibration,
                    checked = settings.bellVibrate,
                    onCheckedChange = { onSettingsChange(settings.copy(bellVibrate = it)) }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = Color(0xFF00FF00),
        modifier = Modifier.padding(vertical = 8.dp)
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF16213E))
            .padding(8.dp),
        content = content
    )
    Spacer(modifier = Modifier.height(16.dp))
}

@Composable
private fun ThemeSelector(
    selectedTheme: String,
    onThemeSelected: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TerminalThemes.ALL_THEMES.forEach { theme ->
            ThemePreviewCard(
                theme = theme,
                isSelected = theme.name == selectedTheme,
                onClick = { onThemeSelected(theme.name) }
            )
        }
    }
}

@Composable
private fun ThemePreviewCard(
    theme: TerminalTheme,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(100.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (isSelected) Modifier.border(2.dp, theme.accent, RoundedCornerShape(8.dp))
                else Modifier
            )
            .clickable(onClick = onClick)
            .background(theme.background)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Mini terminal preview
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Text(
                text = "$ ls -la",
                color = theme.foreground,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "drwxr-xr-x",
                color = theme.blue,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "file.txt",
                color = theme.green,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = theme.name,
            color = theme.foreground,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
    }
}

@Composable
private fun FontSizeSlider(
    fontSize: Float,
    onFontSizeChange: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Font Size",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "${fontSize.toInt()}pt",
                color = Color(0xFF00FF00),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Slider(
            value = fontSize,
            onValueChange = onFontSizeChange,
            valueRange = TerminalSettings.MIN_FONT_SIZE..TerminalSettings.MAX_FONT_SIZE,
            steps = ((TerminalSettings.MAX_FONT_SIZE - TerminalSettings.MIN_FONT_SIZE) / TerminalSettings.FONT_SIZE_STEP).toInt() - 1,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF00FF00),
                activeTrackColor = Color(0xFF00FF00),
                inactiveTrackColor = Color(0xFF0F3460)
            )
        )

        // Preview text
        Text(
            text = "Preview: The quick brown fox",
            color = Color.White,
            fontSize = fontSize.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun CursorStyleSelector(
    style: CursorStyle,
    onStyleChange: (CursorStyle) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CursorStyle.entries.forEach { cursorStyle ->
            CursorStyleOption(
                style = cursorStyle,
                isSelected = style == cursorStyle,
                onClick = { onStyleChange(cursorStyle) }
            )
        }
    }
}

@Composable
private fun CursorStyleOption(
    style: CursorStyle,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val bgColor = if (isSelected) Color(0xFF00FF00) else Color(0xFF0F3460)
    val textColor = if (isSelected) Color.Black else Color.White

    Surface(
        onClick = onClick,
        color = bgColor,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Cursor preview
            Box(
                modifier = Modifier
                    .size(width = 20.dp, height = 24.dp)
                    .background(Color(0xFF1A1A2E))
                    .padding(4.dp)
            ) {
                when (style) {
                    CursorStyle.BLOCK -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.White)
                    )
                    CursorStyle.UNDERLINE -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .align(Alignment.BottomCenter)
                            .background(Color.White)
                    )
                    CursorStyle.BAR -> Box(
                        modifier = Modifier
                            .width(2.dp)
                            .fillMaxHeight()
                            .align(Alignment.CenterStart)
                            .background(Color.White)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = style.name.lowercase().replaceFirstChar { it.uppercase() },
                color = textColor,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun SwitchSetting(
    title: String,
    description: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color(0xFF00FF00),
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = description,
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF00FF00),
                checkedTrackColor = Color(0xFF00FF00).copy(alpha = 0.5f),
                uncheckedThumbColor = Color.Gray,
                uncheckedTrackColor = Color(0xFF0F3460)
            )
        )
    }
}
