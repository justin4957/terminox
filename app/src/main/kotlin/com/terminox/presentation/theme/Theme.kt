package com.terminox.presentation.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Terminal-inspired color palette
private val TerminalGreen = Color(0xFF00FF00)
private val TerminalAmber = Color(0xFFFFB000)
private val DarkBackground = Color(0xFF1A1A2E)
private val DarkSurface = Color(0xFF16213E)

private val DarkColorScheme = darkColorScheme(
    primary = TerminalGreen,
    secondary = TerminalAmber,
    tertiary = Color(0xFF0F3460),
    background = DarkBackground,
    surface = DarkSurface,
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onTertiary = Color.White,
    onBackground = Color(0xFFE4E4E4),
    onSurface = Color(0xFFE4E4E4),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF006D3B),
    secondary = Color(0xFF526350),
    tertiary = Color(0xFF3A656F),
    background = Color(0xFFFCFDF7),
    surface = Color(0xFFFCFDF7),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1A1C19),
    onSurface = Color(0xFF1A1C19),
)

@Composable
fun TerminoxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
