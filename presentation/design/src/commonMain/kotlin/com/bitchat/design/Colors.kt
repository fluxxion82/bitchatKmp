package com.bitchat.design

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

internal val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF39FF14),
    onPrimary = Color.Black,
    secondary = Color(0xFF2ECB10),
    onSecondary = Color.Black,
    background = Color.Black,
    onBackground = Color(0xFF39FF14),
    surface = Color(0xFF111111),
    onSurface = Color(0xFF39FF14),
    error = Color(0xFFFF5555),
    onError = Color.Black
)

internal val LightColorScheme = lightColorScheme(
    primary = Color(0xFF008000),
    onPrimary = Color.White,
    secondary = Color(0xFF006600),
    onSecondary = Color.White,
    background = Color.White,
    onBackground = Color(0xFF008000),
    surface = Color(0xFFF8F8F8),
    onSurface = Color(0xFF008000),
    error = Color(0xFFCC0000),
    onError = Color.White
)

fun currentBackgroundColor(isDarkTheme: Boolean): Color {
    return if (isDarkTheme) {
        DarkColorScheme.background
    } else {
        LightColorScheme.background
    }
}
