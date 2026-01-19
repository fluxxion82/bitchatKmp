package com.bitchat.local.di

import com.bitchat.local.prefs.DesktopEncryptionSettingsFactory
import com.bitchat.local.prefs.EncryptionSettingsFactory
import com.bitchat.local.service.GeocoderService
import com.bitchat.local.service.JvmLocationService
import com.bitchat.local.service.JvmSettingsService
import com.bitchat.local.service.LocationService
import com.bitchat.local.service.SettingsService
import com.bitchat.local.service.impl.StubGeocoderService
import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import org.koin.dsl.module

actual val localModule = module {
    single<Settings.Factory> {
        PreferencesSettings.Factory()
    }

    single<EncryptionSettingsFactory> {
        DesktopEncryptionSettingsFactory()
    }

    single<GeocoderService> { StubGeocoderService() }

    single<SettingsService> { JvmSettingsService() }

    single<LocationService> { JvmLocationService() }
}
