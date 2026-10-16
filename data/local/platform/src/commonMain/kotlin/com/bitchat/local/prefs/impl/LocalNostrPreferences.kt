package com.bitchat.local.prefs.impl

import com.bitchat.nostr.NostrPreferences
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class LocalNostrPreferences(
    settingsFactory: Settings.Factory,
) : NostrPreferences {
    private val settings = settingsFactory.create(PREFS_NAME)
    private val powSettings = settingsFactory.create(POW_PREFS_NAME)

    // Kept in memory: mining is over when the process is, and a StateFlow hands a new collector the value it subscribed at.
    private val mining = MutableStateFlow(false)

    override fun getLastUpdateMs(): Long {
        return settings.getLong(LAST_UPDATE_MS_KEY, 0)
    }

    override fun setLastUpdateMs(value: Long) {
        settings.putLong(LAST_UPDATE_MS_KEY, value)
    }

    override fun setPowEnabled(enabled: Boolean) {
        powSettings.putBoolean(KEY_POW_ENABLED, enabled)
    }

    override fun getPowEnabled(): Boolean {
        return powSettings.getBoolean(KEY_POW_ENABLED, DEFAULT_POW_ENABLED)
    }

    override fun setPowDifficulty(difficulty: Int) {
        powSettings.putInt(KEY_POW_DIFFICULTY, difficulty)
    }

    override fun getPowDifficulty(): Int {
        return powSettings.getInt(KEY_POW_DIFFICULTY, DEFAULT_POW_DIFFICULTY)
    }

    override fun setIsMining(isMining: Boolean) {
        mining.value = isMining
    }

    override fun getIsMiningFlow(): Flow<Boolean> = mining.asStateFlow()

    companion object {
        private const val PREFS_NAME = "relay_prefs"
        private const val LAST_UPDATE_MS_KEY = "last_update_ms"

        private const val POW_PREFS_NAME = "pow_preferences"
        private const val KEY_POW_ENABLED = "pow_enabled"
        private const val KEY_POW_DIFFICULTY = "pow_difficulty"

        private const val DEFAULT_POW_ENABLED = false
        private const val DEFAULT_POW_DIFFICULTY = 12 // Reasonable default for geohash spam prevention
    }
}
