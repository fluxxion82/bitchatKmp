package com.bitchat.local.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

class AndroidSettingsService(
    private val context: Context,
) : SettingsService {
    override fun isBatteryOptimizationSupported(): Boolean {
        return true
    }

    override fun isBatteryOptimizationDisabled(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isIgnoring = powerManager.isIgnoringBatteryOptimizations(context.packageName)

        return isIgnoring
    }

    override fun disableBatteryOptimization() {
        val intent = Intent().apply {
            action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            data = Uri.parse("package:${context.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
//            val batteryOptimizationLauncher = activityProvider.get()?.regi.registerForActivityResult(
//                ActivityResultContracts.StartActivityForResult()
//            ) { result ->
//            }

        } else {
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            context.startActivity(intent)
        }
    }
}
