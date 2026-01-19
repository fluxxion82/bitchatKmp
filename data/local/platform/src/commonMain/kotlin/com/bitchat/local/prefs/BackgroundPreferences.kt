package com.bitchat.local.prefs

import com.bitchat.domain.app.model.BackgroundMode

interface BackgroundPreferences {
    fun setBackgroundMode(mode: BackgroundMode)
    fun getBackgroundMode(): BackgroundMode
}
