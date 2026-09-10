package com.bitchat.local.prefs.impl

import com.bitchat.domain.tor.model.TorMode
import com.bitchat.local.prefs.TorPreferences
import com.bitchat.local.prefs.platformDefaultTorMode
import com.russhwolf.settings.Settings

internal class LocalTorPreferences(
    settingsFactory: Settings.Factory,
    private val defaultMode: TorMode = platformDefaultTorMode,
) : TorPreferences {
    private val settings = settingsFactory.create(PREFS_NAME)

    override fun setTorMode(mode: TorMode) {
        settings.putString(TOR_MODE_KEY, mode.name)
    }

    /**
     * Reads an absent key as [defaultMode] rather than a fixed ON.
     *
     * getStringOrNull, not getString with a default, because the two cases have to stay
     * distinguishable: an explicitly stored choice is honoured on every platform, and only a key
     * that was never written falls back. The key is never written by reading it, so a fresh
     * install stays at the platform default until the user chooses.
     */
    override fun getTorMode(): TorMode {
        val stored = settings.getStringOrNull(TOR_MODE_KEY) ?: return defaultMode
        return try {
            TorMode.valueOf(stored)
        } catch (e: IllegalArgumentException) {
            // Same default as an absent key. Returning ON here would reintroduce the trap through
            // a corrupted value.
            defaultMode
        }
    }

    companion object {
        private const val PREFS_NAME = "tor_settings"
        private const val TOR_MODE_KEY = "tor_mode"
    }
}
