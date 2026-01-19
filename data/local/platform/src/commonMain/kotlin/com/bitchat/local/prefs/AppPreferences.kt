package com.bitchat.local.prefs

import com.bitchat.domain.app.model.AppTheme
import com.bitchat.domain.initialization.models.AppVersionInfo

interface AppPreferences {
    suspend fun updateVersionInfo(state: AppVersionInfo)
    suspend fun getVersionInfo(): AppVersionInfo?

    suspend fun setAppTheme(theme: AppTheme)
    suspend fun getAppTheme(): AppTheme

    suspend fun setBatteryOptimizationSkipped(skipped: Boolean)
    suspend fun isBatteryOptimizationSkipped(): Boolean
}
