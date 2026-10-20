package com.catlaser.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CatLaserColorScheme = darkColorScheme(
    primary = AccentRed,
    secondary = AccentOrange,
    tertiary = AccentPurple,
    background = BgDark,
    surface = CardDark,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = TextLight,
    onSurface = TextLight,
    outline = BorderDark
)

@Composable
fun CatLaserTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // The app deliberately always uses the dark theme, like the web UI.
    MaterialTheme(
        colorScheme = CatLaserColorScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}
