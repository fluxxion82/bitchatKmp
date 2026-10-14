package com.bitchat.local.prefs.impl

import com.bitchat.domain.location.model.GeohashChannelLevel
import com.bitchat.local.prefs.GeohashPreferences
import com.russhwolf.settings.Settings

class LocalGeohashPreferences(
    private val settingsFactory: Settings.Factory,
) : GeohashPreferences {
    private val settings = settingsFactory.create(PREFS_NAME)

    override fun saveLevel(level: GeohashChannelLevel) =
        settings.putString(CHANNEL_LEVEL, level.name)

    override fun getLevel(): GeohashChannelLevel? =
        settings.getStringOrNull(CHANNEL_LEVEL)?.let { runCatching { GeohashChannelLevel.valueOf(it) }.getOrNull() }

    override fun saveLocationServicesEnabled(enabled: Boolean) {
        settings.putBoolean(LOCATION_SERVICES_ENABLED, enabled)
    }

    override fun getLocationServicesEnabled(): Boolean {
        return settings.getBooleanOrNull(LOCATION_SERVICES_ENABLED) ?: true
    }

    override fun getSavedPlace(): String? = settings.getStringOrNull(SAVED_PLACE)

    override fun saveSavedPlace(geohash: String) {
        settings.putString(SAVED_PLACE, geohash)
    }

    override fun clearSavedPlace() {
        settings.remove(SAVED_PLACE)
    }

    companion object {
        private const val PREFS_NAME = "geo_prefs"

        private const val CHANNEL_LEVEL = "level"
        private const val LOCATION_SERVICES_ENABLED = "location_services_enabled"
        private const val SAVED_PLACE = "saved_place"

        private const val STORE_KEY = "bookmarks"
        private const val NAMES_STORE_KEY = "bookmarkNames"
    }
}
