package com.bitchat.repo.repositories

import com.bitchat.bluetooth.service.BluetoothConnectionService
import com.bitchat.domain.app.model.AppTheme
import com.bitchat.domain.app.model.BackgroundMode
import com.bitchat.domain.app.model.BatteryOptimizationStatus
import com.bitchat.domain.app.repository.AppRepository
import com.bitchat.domain.base.CoroutinesContextFacade
import com.bitchat.local.prefs.AppPreferences
import com.bitchat.local.prefs.BackgroundPreferences
import com.bitchat.local.service.SettingsService
import com.bitchat.repo.background.BackgroundServiceController
import kotlinx.coroutines.withContext

class AppRepo(
    private val coroutinesContextFacade: CoroutinesContextFacade,
    private val appPreferences: AppPreferences,
    private val backgroundPreferences: BackgroundPreferences,
    private val backgroundServiceController: BackgroundServiceController,
    private val bluetoothConnectionService: BluetoothConnectionService,
    private val settingsService: SettingsService,
) : AppRepository {
    override suspend fun getAppTheme(): AppTheme = withContext(coroutinesContextFacade.io) {
        appPreferences.getAppTheme()
    }

    override suspend fun setAppTheme(theme: AppTheme) = withContext(coroutinesContextFacade.io) {
        appPreferences.setAppTheme(theme)
    }

    override suspend fun hasRequiredPermissions(): Boolean = withContext(coroutinesContextFacade.io) {
        bluetoothConnectionService.hasRequiredPermissions()
    }

    override suspend fun isBatteryOptimizationSkipped(): Boolean = withContext(coroutinesContextFacade.io) {
        appPreferences.isBatteryOptimizationSkipped()
    }

    override suspend fun setBatteryOptimizationSkipped(skipped: Boolean) = withContext(coroutinesContextFacade.io) {
        appPreferences.setBatteryOptimizationSkipped(skipped)
    }

    override suspend fun getBatteryOptimizationStatus(): BatteryOptimizationStatus = withContext(coroutinesContextFacade.io) {
        when {
            !settingsService.isBatteryOptimizationSupported() -> BatteryOptimizationStatus.NOT_SUPPORTED
            settingsService.isBatteryOptimizationDisabled() -> BatteryOptimizationStatus.DISABLED
            else -> BatteryOptimizationStatus.ENABLED
        }
    }

    override suspend fun disableBatteryOptimization() = withContext(coroutinesContextFacade.io) {
        settingsService.disableBatteryOptimization()
    }

    override suspend fun getBackgroundMode(): BackgroundMode = withContext(coroutinesContextFacade.io) {
        backgroundPreferences.getBackgroundMode()
    }

    override suspend fun setBackgroundMode(mode: BackgroundMode) = withContext(coroutinesContextFacade.io) {
        backgroundPreferences.setBackgroundMode(mode)
    }

    override suspend fun enableBackgroundMode() = withContext(coroutinesContextFacade.io) {
        backgroundPreferences.setBackgroundMode(BackgroundMode.ON)
        backgroundServiceController.startForegroundService()
    }

    override suspend fun disableBackgroundMode() = withContext(coroutinesContextFacade.io) {
        backgroundPreferences.setBackgroundMode(BackgroundMode.OFF)
        backgroundServiceController.stopForegroundService()
    }

    override suspend fun clearData() = withContext(coroutinesContextFacade.io) {
        setAppTheme(AppTheme.SYSTEM)
        setBatteryOptimizationSkipped(false)
    }
}
