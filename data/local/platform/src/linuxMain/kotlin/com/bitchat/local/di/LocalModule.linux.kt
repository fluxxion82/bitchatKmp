package com.bitchat.local.di

import com.bitchat.local.prefs.EncryptionSettingsFactory
import com.bitchat.local.prefs.LinuxEncryptionSettingsFactory
import com.bitchat.local.prefs.LinuxFileSettings
import com.bitchat.local.service.GeocoderService
import com.bitchat.local.service.LinuxLocationService
import com.bitchat.local.service.LinuxSettingsService
import com.bitchat.local.service.LocationService
import com.bitchat.local.service.SettingsService
import com.bitchat.local.service.impl.StubGeocoderService
import com.russhwolf.settings.Settings
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import org.koin.dsl.module
import platform.posix.getenv
import platform.posix.mkdir

@OptIn(ExperimentalForeignApi::class)
actual val localModule = module {
    single<Settings.Factory> {
        LinuxSettingsFactory()
    }

    single<EncryptionSettingsFactory> {
        LinuxEncryptionSettingsFactory()
    }

    single<GeocoderService> { StubGeocoderService() }

    single<SettingsService> { LinuxSettingsService() }

    single<LocationService> { LinuxLocationService() }
}

/**
 * Linux-specific Settings.Factory implementation.
 * Creates file-based settings in ~/.bitchat/settings/
 */
@OptIn(ExperimentalForeignApi::class)
class LinuxSettingsFactory : Settings.Factory {
    private val settingsDir: String by lazy {
        val home = getenv("HOME")?.toKString() ?: "/tmp"
        val baseDir = "$home/.bitchat"
        val dir = "$baseDir/settings"
        mkdir(baseDir, 0x1C0u) // 0700
        mkdir(dir, 0x1C0u)
        dir
    }

    override fun create(name: String?): Settings {
        val filename = name ?: "default"
        return LinuxFileSettings("$settingsDir/$filename.prefs")
    }
}
