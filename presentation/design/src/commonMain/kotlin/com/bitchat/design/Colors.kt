package com.bitchat.design

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.bitchat.viewvo.theme.DarkPalette
import com.bitchat.viewvo.theme.LightPalette

// The values come from presentation:viewvo, so the terminal UI draws bitchat in the same colours.
internal val DarkColorScheme = darkColorScheme(
    primary = Color(DarkPalette.primary),
    onPrimary = Color(DarkPalette.onPrimary),
    secondary = Color(DarkPalette.secondary),
    onSecondary = Color(DarkPalette.onSecondary),
    background = Color(DarkPalette.background),
    onBackground = Color(DarkPalette.onBackground),
    surface = Color(DarkPalette.surface),
    onSurface = Color(DarkPalette.onSurface),
    error = Color(DarkPalette.error),
    onError = Color(DarkPalette.onError)
)

internal val LightColorScheme = lightColorScheme(
    primary = Color(LightPalette.primary),
    onPrimary = Color(LightPalette.onPrimary),
    secondary = Color(LightPalette.secondary),
    onSecondary = Color(LightPalette.onSecondary),
    background = Color(LightPalette.background),
    onBackground = Color(LightPalette.onBackground),
    surface = Color(LightPalette.surface),
    onSurface = Color(LightPalette.onSurface),
    error = Color(LightPalette.error),
    onError = Color(LightPalette.onError)
)

fun currentBackgroundColor(isDarkTheme: Boolean): Color {
    return if (isDarkTheme) {
        DarkColorScheme.background
    } else {
        LightColorScheme.background
    }
}
