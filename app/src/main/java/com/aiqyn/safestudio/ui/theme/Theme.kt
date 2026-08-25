package com.aiqyn.safestudio.ui.theme

import android.app.Activity
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
    primary = CosmicPurple,
    secondary = CosmicBlue,
    tertiary = GlowCyan,
    background = DeepSpaceBackground,
    surface = SpaceNavy,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White,
    error = Color(0xFFFF5252),
    onError = Color.White,
    primaryContainer = CosmicPurple.copy(alpha = 0.2f),
    onPrimaryContainer = Color.White
)

@Composable
fun AIqynTheme(
    darkTheme: Boolean = true, // Force dark theme for deep space look
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
