package com.bitchat.local.di

import com.bitchat.domain.initialization.AppInitializer
import com.bitchat.local.prefs.AndroidEncryptionSettingsFactory
import com.bitchat.local.prefs.EncryptionSettingsFactory
import com.bitchat.local.service.ActivityProvider
import com.bitchat.local.service.AndroidLocationService
import com.bitchat.local.service.AndroidSettingsService
import com.bitchat.local.service.GeocoderService
import com.bitchat.local.service.LocationService
import com.bitchat.local.service.SettingsService
import com.bitchat.local.service.impl.AndroidGeocoderService
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.bind
import org.koin.dsl.module

actual val localModule = module {
    single<Settings.Factory> { SharedPreferencesSettings.Factory(androidContext()) }
    single<EncryptionSettingsFactory> { AndroidEncryptionSettingsFactory(androidContext()) }

    single { ActivityProvider(application = androidApplication()) } bind AppInitializer::class

    single<SettingsService> { AndroidSettingsService(androidContext()) }
    single<LocationService> { AndroidLocationService(androidContext()) }
    single<GeocoderService> { AndroidGeocoderService(androidContext()) }
}
