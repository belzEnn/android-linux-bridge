package io.github.belzenn.androidlinuxbridge.ui.theme

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFAFC8E6),
    onPrimary = Color(0xFF23384F),
    primaryContainer = Color(0xFF334C66),
    onPrimaryContainer = Color(0xFFD6E6FA),
    secondary = Color(0xFFBAC5D1),
    onSecondary = Color(0xFF2C3640),
    secondaryContainer = Color(0xFF303942),
    onSecondaryContainer = Color(0xFFEDF0F3),
    tertiary = Color(0xFF9BC9AE),
    onTertiary = Color(0xFF15382D),
    tertiaryContainer = Color(0xFF284D40),
    onTertiaryContainer = Color(0xFFC8EBDD),
    background = Color(0xFF191C20),
    onBackground = Color(0xFFE9EDF1),
    surface = Color(0xFF191C20),
    onSurface = Color(0xFFE9EDF1),
    surfaceVariant = Color(0xFF343B43),
    onSurfaceVariant = Color(0xFFB9C2CC),
    surfaceContainerLowest = Color(0xFF15171A),
    surfaceContainerLow = Color(0xFF20242A),
    surfaceContainer = Color(0xFF24292F),
    surfaceContainerHigh = Color(0xFF2A3037),
    surfaceContainerHighest = Color(0xFF323942),
    inverseSurface = Color(0xFFE9EDF1),
    inverseOnSurface = Color(0xFF252A31),
    inversePrimary = Color(0xFF395779),
    outline = Color(0xFF87939F),
    outlineVariant = Color(0xFF3C444F),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF395779),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE8F5),
    onPrimaryContainer = Color(0xFF253D58),
    secondary = Color(0xFF546271),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3E8EE),
    onSecondaryContainer = Color(0xFF263442),
    tertiary = Color(0xFF32654E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCFEADD),
    onTertiaryContainer = Color(0xFF234B3D),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF20262D),
    surface = Color(0xFFF7F8FA),
    onSurface = Color(0xFF20262D),
    surfaceVariant = Color(0xFFE5E9EE),
    onSurfaceVariant = Color(0xFF515D69),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF0F2F5),
    surfaceContainer = Color(0xFFEBEEF2),
    surfaceContainerHigh = Color(0xFFE5E9EE),
    surfaceContainerHighest = Color(0xFFDEE4EB),
    inverseSurface = Color(0xFF2B3036),
    inverseOnSurface = Color(0xFFEEF1F5),
    inversePrimary = Color(0xFFAFC8E6),
    outline = Color(0xFF707C88),
    outlineVariant = Color(0xFFCCD3DC),
)

internal fun appearancePreferences(context: Context) =
    context.getSharedPreferences("bridge_appearance", Context.MODE_PRIVATE)

@Composable
internal fun useDarkTheme(preference: String?): Boolean = when (preference) {
    "light" -> false
    "dark" -> true
    else -> isSystemInDarkTheme()
}

@Composable
fun AndroidLinuxBridgeTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
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
