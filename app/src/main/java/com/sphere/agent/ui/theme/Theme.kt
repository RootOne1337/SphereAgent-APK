package com.sphere.agent.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * SphereAgent Material 3 Theme
 * 
 * Современная тема 2025 с:
 * - Dynamic colors (Android 12+)
 * - Dark/Light режимы
 * - Custom color scheme
 */

// Primary Brand Colors - Cyan/Teal
private val CyanPrimary = Color(0xFF00BCD4)
private val CyanDark = Color(0xFF0097A7)
private val CyanLight = Color(0xFFB2EBF2)

// Accent Colors
private val AccentOrange = Color(0xFFFF9800)
private val AccentGreen = Color(0xFF4CAF50)
private val AccentRed = Color(0xFFF44336)

// Dark Theme Colors
private val DarkColorScheme = darkColorScheme(
    primary = CyanPrimary,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF004D40),
    onPrimaryContainer = Color(0xFFB2EBF2),
    
    secondary = AccentOrange,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF3E2723),
    onSecondaryContainer = Color(0xFFFFE0B2),
    
    tertiary = AccentGreen,
    onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF1B5E20),
    onTertiaryContainer = Color(0xFFC8E6C9),
    
    error = AccentRed,
    onError = Color.White,
    errorContainer = Color(0xFFB71C1C),
    onErrorContainer = Color(0xFFFFCDD2),
    
    background = Color(0xFF121212),
    onBackground = Color(0xFFE0E0E0),
    
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFE0E0E0),
    
    surfaceVariant = Color(0xFF2D2D2D),
    onSurfaceVariant = Color(0xFFBDBDBD),
    
    outline = Color(0xFF757575),
    outlineVariant = Color(0xFF424242),
    
    inverseSurface = Color(0xFFE0E0E0),
    inverseOnSurface = Color(0xFF121212),
    inversePrimary = CyanDark
)

// Light Theme Colors
private val LightColorScheme = lightColorScheme(
    primary = CyanDark,
    onPrimary = Color.White,
    primaryContainer = CyanLight,
    onPrimaryContainer = Color(0xFF00363D),
    
    secondary = Color(0xFFF57C00),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE0B2),
    onSecondaryContainer = Color(0xFF3E2723),
    
    tertiary = Color(0xFF388E3C),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC8E6C9),
    onTertiaryContainer = Color(0xFF1B5E20),
    
    error = AccentRed,
    onError = Color.White,
    errorContainer = Color(0xFFFFCDD2),
    onErrorContainer = Color(0xFFB71C1C),
    
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF212121),
    
    surface = Color.White,
    onSurface = Color(0xFF212121),
    
    surfaceVariant = Color(0xFFF5F5F5),
    onSurfaceVariant = Color(0xFF616161),
    
    outline = Color(0xFFBDBDBD),
    outlineVariant = Color(0xFFE0E0E0),
    
    inverseSurface = Color(0xFF303030),
    inverseOnSurface = Color(0xFFFAFAFA),
    inversePrimary = CyanPrimary
)

// Custom Shapes
val SphereShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun SphereAgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        // Dynamic color на Android 12+ (S = API 31)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            try {
                val window = (view.context as Activity).window
                window.statusBarColor = colorScheme.background.toArgb()
                // WindowCompat доступен с API 21, но isAppearanceLightStatusBars может крашнуться
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
                }
            } catch (e: Exception) {
                // Игнорируем ошибки на старых устройствах
            }
        }
    }
    
    MaterialTheme(
        colorScheme = colorScheme,
        typography = SphereTypography,
        shapes = SphereShapes,
        content = content
    )
}
