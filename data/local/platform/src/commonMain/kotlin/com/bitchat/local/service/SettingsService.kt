package com.bitchat.local.service

interface SettingsService {
    fun isBatteryOptimizationSupported(): Boolean
    fun isBatteryOptimizationDisabled(): Boolean
    fun disableBatteryOptimization()
}
