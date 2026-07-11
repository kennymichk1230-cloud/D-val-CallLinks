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

private val DarkColorScheme = darkColorScheme(
    primary = CyberCyan,
    onPrimary = Color.Black,
    primaryContainer = ElectricTeal,
    secondary = ElectricTeal,
    background = DeepCharcoal,
    onBackground = Color.White,
    surface = SlateCard,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF222634),
    onSurfaceVariant = Color(0xFFC4C9D6),
    error = SignalCoral,
    onError = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF007A87),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F7FA),
    secondary = Color(0xFF00BFA5),
    background = Color(0xFFF8F9FC),
    onBackground = Color(0xFF151821),
    surface = Color.White,
    onSurface = Color(0xFF151821),
    surfaceVariant = Color(0xFFECEFF1),
    onSurfaceVariant = Color(0xFF37474F),
    error = Color(0xFFD32F2F),
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Disable dynamic colors to keep our premium Cyber theme consistent!
    content: @Composable () -> Unit,
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
