package com.bitchat.local.prefs.impl

import com.bitchat.domain.app.model.AppTheme
import com.bitchat.domain.initialization.models.AppVersionInfo
import com.bitchat.local.prefs.AppPreferences
import com.russhwolf.settings.Settings

internal class LocalAppPreferences(
    settingsFactory: Settings.Factory,
) : AppPreferences {
    private val settings = settingsFactory.create(PREFS_NAME)

    override suspend fun updateVersionInfo(state: AppVersionInfo) {
        val upgradeRequired = when (state) {
            AppVersionInfo.SUPPORTED -> false
            AppVersionInfo.UPGRADE_REQUIRED -> true
        }

        settings.putBoolean(APP_VERSION_INFO_KEY, upgradeRequired)
    }

    override suspend fun getVersionInfo(): AppVersionInfo? {
        val info = settings.getBooleanOrNull(APP_VERSION_INFO_KEY)
        return when (info) {
            true -> AppVersionInfo.UPGRADE_REQUIRED
            false -> AppVersionInfo.SUPPORTED
            null -> null
        }
    }

    override suspend fun setAppTheme(theme: AppTheme) {
        settings.putInt(APP_THEME_KEY, theme.ordinal)
    }

    override suspend fun getAppTheme(): AppTheme {
        return AppTheme.entries[settings.getInt(APP_THEME_KEY, AppTheme.SYSTEM.ordinal)]
    }

    override suspend fun setBatteryOptimizationSkipped(enabled: Boolean) {
        settings.putBoolean(BATTERY_OPTIMIZATION_KEY, enabled)
    }

    override suspend fun isBatteryOptimizationSkipped(): Boolean {
        return settings.getBooleanOrNull(BATTERY_OPTIMIZATION_KEY) == true
    }

    companion object {
        private const val PREFS_NAME = "app_version"
        private const val APP_VERSION_INFO_KEY = "app_version_obsolete"

        private const val APP_THEME_KEY = "app_theme"
        private const val BATTERY_OPTIMIZATION_KEY = "battery_optimization"
    }
}
