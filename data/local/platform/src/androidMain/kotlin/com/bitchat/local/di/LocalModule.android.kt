package com.bitchat.local.di

import com.bitchat.domain.initialization.AppInitializer
import com.bitchat.local.identity.DomainInspector
import com.bitchat.local.identity.LedgerStore
import com.bitchat.local.identity.NoDomainInspector
import com.bitchat.local.identity.NoLedgerStore
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

    // No directory domain and no ledger: this store raises a read failure as an exception
    // rather than handing back a half-loaded map, so the identity guard reproduces the
    // pre-custodian decision exactly. See IdentityMintGate.
    single<DomainInspector> { NoDomainInspector }
    single<LedgerStore> { NoLedgerStore }

    single<EncryptionSettingsFactory> { AndroidEncryptionSettingsFactory(androidContext()) }

    single { ActivityProvider(application = androidApplication()) } bind AppInitializer::class

    single<SettingsService> { AndroidSettingsService(androidContext()) }
    single<LocationService> { AndroidLocationService(androidContext()) }
    single<GeocoderService> { AndroidGeocoderService(androidContext()) }
}
