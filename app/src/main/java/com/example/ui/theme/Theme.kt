package com.example.ui.theme

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

private val CleanLightColorScheme = lightColorScheme(
    primary = PrimaryBlue,
    onPrimary = Color.White,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    secondary = Color(0xFF535F70),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7E3F7),
    onSecondaryContainer = Color(0xFF101C2B),
    tertiary = AccentGreen,
    onTertiary = Color.White,
    background = CleanBg,
    onBackground = TextPrimary,
    surface = CleanSurface,
    onSurface = TextPrimary,
    surfaceVariant = CleanChipBg,
    onSurfaceVariant = TextSecondary,
    outline = CleanBorder,
    error = AccentRed,
    onError = Color.White
)

private val CleanDarkColorScheme = darkColorScheme(
    primary = Color(0xFFA1C9FF),
    onPrimary = Color(0xFF00325A),
    primaryContainer = Color(0xFF00497E),
    onPrimaryContainer = PrimaryContainer,
    secondary = Color(0xFFBBC7DB),
    onSecondary = Color(0xFF253140),
    tertiary = AccentGreen,
    background = Color(0xFF111418),
    onBackground = Color(0xFFE2E2E6),
    surface = Color(0xFF1A1C1E),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF2E3135),
    onSurfaceVariant = Color(0xFFC4C6D0),
    outline = Color(0xFF44474E),
    error = Color(0xFFFFB4AB)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = false, // Clean Minimalism design is optimized for crisp high-contrast light theme
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> CleanDarkColorScheme
        else -> CleanLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}


