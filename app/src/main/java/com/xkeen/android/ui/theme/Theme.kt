package com.xkeen.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF90CAF9),
    onPrimary = Color(0xFF0D1B2A),
    primaryContainer = Color(0xFF1B3A5C),
    onPrimaryContainer = Color(0xFFD6EAFF),
    secondary = Color(0xFF80CBC4),
    onSecondary = Color(0xFF0A2724),
    secondaryContainer = Color(0xFF1A4A44),
    onSecondaryContainer = Color(0xFFCCF2EE),
    tertiary = Color(0xFFFFAB91),
    surface = Color(0xFF0F1419),
    onSurface = Color(0xFFE8ECF0),
    surfaceVariant = Color(0xFF1C2128),
    onSurfaceVariant = Color(0xFFA8B2BD),
    background = Color(0xFF0A0E12),
    error = Color(0xFFEF5350),
    outline = Color(0xFF3D4752)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6EAFF),
    onPrimaryContainer = Color(0xFF0D1B2A),
    secondary = Color(0xFF00897B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCF2EE),
    onSecondaryContainer = Color(0xFF0A2724),
    tertiary = Color(0xFFE64A19),
    surface = Color(0xFFF8FAFB),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFEEF1F4),
    onSurfaceVariant = Color(0xFF44474A),
    background = Color(0xFFF0F3F5),
    error = Color(0xFFD32F2F),
    outline = Color(0xFFC4C8CC)
)

@Composable
fun XkeenTheme(
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
        typography = Typography(),
        content = content
    )
}
