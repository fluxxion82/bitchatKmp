package com.bitchat.domain.app.repository

import com.bitchat.domain.app.model.AppTheme
import com.bitchat.domain.app.model.BackgroundMode
import com.bitchat.domain.app.model.BatteryOptimizationStatus

interface AppRepository {
    suspend fun getAppTheme(): AppTheme
    suspend fun setAppTheme(theme: AppTheme)

    suspend fun hasRequiredPermissions(): Boolean

    suspend fun isBatteryOptimizationSkipped(): Boolean
    suspend fun setBatteryOptimizationSkipped(skipped: Boolean)

    suspend fun getBatteryOptimizationStatus(): BatteryOptimizationStatus
    suspend fun disableBatteryOptimization()

    suspend fun getBackgroundMode(): BackgroundMode
    suspend fun setBackgroundMode(mode: BackgroundMode)
    suspend fun enableBackgroundMode()
    suspend fun disableBackgroundMode()

    suspend fun clearData()
}
