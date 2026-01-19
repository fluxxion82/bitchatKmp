package com.bitchat.local.prefs

import com.bitchat.domain.location.model.GeohashChannelLevel

interface GeohashPreferences {
    fun saveLevel(level: GeohashChannelLevel)
    fun getLevel(): GeohashChannelLevel?
    fun saveLocationServicesEnabled(enabled: Boolean)
    fun getLocationServicesEnabled(): Boolean
}
