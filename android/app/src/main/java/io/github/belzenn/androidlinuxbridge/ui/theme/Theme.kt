package io.github.belzenn.androidlinuxbridge.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF99C1F1),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF24496A),
    onPrimaryContainer = Color(0xFFD3E4FA),
    secondaryContainer = Color(0xFF34465A),
    onSecondaryContainer = Color(0xFFD3E4FA),
    surfaceContainerHigh = Color(0xFF363636),
    surfaceContainerHighest = Color(0xFF414141),
    background = Color(0xFF242424),
    surface = Color(0xFF242424),
    surfaceContainerLow = Color(0xFF202020),
    surfaceContainer = Color(0xFF303030),
    surfaceVariant = Color(0xFF3A3A3A),
    onBackground = Color(0xFFF6F5F4),
    onSurface = Color(0xFFF6F5F4),
    onSurfaceVariant = Color(0xFFC5C3C1)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1C71D8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEBFF),
    onPrimaryContainer = Color(0xFF12365D),
    secondaryContainer = Color(0xFFDCEBFF),
    onSecondaryContainer = Color(0xFF12365D),
    surfaceContainerHigh = Color(0xFFE5E5E5),
    surfaceContainerHighest = Color(0xFFDEDEDE),
    background = Color(0xFFFAFAFA),
    surface = Color(0xFFFAFAFA),
    surfaceContainerLow = Color(0xFFF0F0F0),
    surfaceContainer = Color(0xFFEAEAEA),
    surfaceVariant = Color(0xFFE5E5E5),
    onBackground = Color(0xFF242424),
    onSurface = Color(0xFF242424),
    onSurfaceVariant = Color(0xFF605E5C)
)

@Composable
fun AndroidLinuxBridgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
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
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
    }
    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
