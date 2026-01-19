package com.bitchat.domain.app.model

sealed class AppEvent {
    data object ThemeUpdated : AppEvent()
    data object BackgroundModeChanged : AppEvent()
}
