package com.bitchat.local.prefs.impl

import com.bitchat.domain.tor.model.TorMode
import com.bitchat.local.prefs.TorPreferences
import com.russhwolf.settings.Settings

internal class LocalTorPreferences(
    settingsFactory: Settings.Factory
) : TorPreferences {
    private val settings = settingsFactory.create(PREFS_NAME)

    override fun setTorMode(mode: TorMode) {
        settings.putString(TOR_MODE_KEY, mode.name)
    }

    override fun getTorMode(): TorMode {
        val modeString = settings.getString(TOR_MODE_KEY, TorMode.ON.name)
        return try {
            TorMode.valueOf(modeString)
        } catch (e: IllegalArgumentException) {
            TorMode.ON
        }
    }

    companion object {
        private const val PREFS_NAME = "tor_settings"
        private const val TOR_MODE_KEY = "tor_mode"
    }
}
