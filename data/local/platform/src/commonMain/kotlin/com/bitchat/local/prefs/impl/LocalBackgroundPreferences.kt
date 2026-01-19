package com.bitchat.local.prefs.impl

import com.bitchat.domain.app.model.BackgroundMode
import com.bitchat.local.prefs.BackgroundPreferences
import com.russhwolf.settings.Settings

internal class LocalBackgroundPreferences(
    settingsFactory: Settings.Factory
) : BackgroundPreferences {
    private val settings = settingsFactory.create(PREFS_NAME)

    override fun setBackgroundMode(mode: BackgroundMode) {
        settings.putString(BACKGROUND_MODE_KEY, mode.name)
    }

    override fun getBackgroundMode(): BackgroundMode {
        val modeString = settings.getString(BACKGROUND_MODE_KEY, BackgroundMode.OFF.name)
        return try {
            BackgroundMode.valueOf(modeString)
        } catch (e: IllegalArgumentException) {
            BackgroundMode.OFF
        }
    }

    companion object {
        private const val PREFS_NAME = "background_settings"
        private const val BACKGROUND_MODE_KEY = "background_mode"
    }
}
