package com.bitchat.local.di

import com.bitchat.local.prefs.EncryptionSettingsFactory
import com.bitchat.local.prefs.NativeEncryptionSettingsFactory
import com.bitchat.local.service.GeocoderService
import com.bitchat.local.service.IosSettingsService
import com.bitchat.local.service.LocationService
import com.bitchat.local.service.MacosLocationService
import com.bitchat.local.service.SettingsService
import com.bitchat.local.service.impl.StubGeocoderService
import com.russhwolf.settings.NSUserDefaultsSettings
import com.russhwolf.settings.Settings
import org.koin.dsl.module

actual val localModule = module {
    single<Settings.Factory> { NSUserDefaultsSettings.Factory() }
    single<EncryptionSettingsFactory> { NativeEncryptionSettingsFactory() }

    single<SettingsService> { IosSettingsService() }
    single<LocationService> { MacosLocationService() }

    single<GeocoderService> { StubGeocoderService() }
}
