package com.bitchat.viewvo.settings

import com.bitchat.domain.app.model.AppTheme

fun ThemePreference.toDomain(): AppTheme {
    return when (this) {
        ThemePreference.SYSTEM -> AppTheme.SYSTEM
        ThemePreference.LIGHT -> AppTheme.LIGHT
        ThemePreference.DARK -> AppTheme.DARK
    }
}

fun AppTheme.toThemePreference(): ThemePreference {
    return when (this) {
        AppTheme.SYSTEM -> ThemePreference.SYSTEM
        AppTheme.LIGHT -> ThemePreference.LIGHT
        AppTheme.DARK -> ThemePreference.DARK
    }
}
