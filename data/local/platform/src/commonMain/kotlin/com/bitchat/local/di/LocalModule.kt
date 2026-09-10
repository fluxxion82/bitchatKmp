package com.bitchat.local.di

import com.bitchat.cache.di.cacheModule
import com.bitchat.domain.connectivity.repository.ConnectivityRepository
import com.bitchat.local.identity.DomainInspector
import com.bitchat.local.identity.LedgerStore
import com.bitchat.local.prefs.AppPreferences
import com.bitchat.local.prefs.BackgroundPreferences
import com.bitchat.local.prefs.BlockListPreferences
import com.bitchat.local.prefs.BookmarkPreferences
import com.bitchat.local.prefs.ChannelPreferences
import com.bitchat.local.prefs.GeohashPreferences
import com.bitchat.local.prefs.LoRaPreferences
import com.bitchat.local.prefs.SecureIdentityPreferences
import com.bitchat.domain.tor.MutableRequestedTorIntent
import com.bitchat.domain.tor.RequestedTorIntent
import com.bitchat.local.prefs.TorPreferences
import com.bitchat.local.prefs.UserPreferences
import com.bitchat.local.prefs.impl.LocalAppPreferences
import com.bitchat.local.prefs.impl.LocalBackgroundPreferences
import com.bitchat.local.prefs.impl.LocalBlockListPreferences
import com.bitchat.local.prefs.impl.LocalBookmarkPreferences
import com.bitchat.local.prefs.impl.LocalChannelPreferences
import com.bitchat.local.prefs.impl.LocalGeohashPreferences
import com.bitchat.local.prefs.impl.LocalLoRaPreferences
import com.bitchat.local.prefs.impl.LocalNostrPreferences
import com.bitchat.local.prefs.impl.LocalSecureIdentityPreferences
import com.bitchat.local.prefs.impl.LocalTorPreferences
import com.bitchat.local.tor.LocalRequestedTorIntent
import com.bitchat.local.prefs.impl.LocalUserPreferences
import com.bitchat.local.repository.LocalConnectivityRepository
import com.bitchat.local.transport.SecureTransportIdentityProvider
import com.bitchat.nostr.NostrPreferences
import com.bitchat.transport.TransportIdentityProvider
import org.koin.core.module.Module
import org.koin.dsl.module

expect val localModule: Module

val commonLocal = module {
    includes(cacheModule)

    single<AppPreferences> { LocalAppPreferences(settingsFactory = get()) }
    single<UserPreferences> { LocalUserPreferences(encryptedPreferenceFactory = get()) }
    // The identity store owns the one custodian that may create identity key material, so it
    // needs the two things that custodian consults. Every platform's localModule registers a
    // DomainInspector and a LedgerStore; all but the embedded Linux build register the no-op
    // pair, which reproduces the pre-custodian behaviour exactly.
    single<SecureIdentityPreferences> {
        LocalSecureIdentityPreferences(
            encryptedPreferenceFactory = get(),
            domainInspector = get<DomainInspector>(),
            ledgerStore = get<LedgerStore>(),
        )
    }
    single<NostrPreferences> { LocalNostrPreferences(settingsFactory = get()) }
    single<GeohashPreferences> { LocalGeohashPreferences(settingsFactory = get()) }
    single<ChannelPreferences> { LocalChannelPreferences(settingsFactory = get()) }
    single<BookmarkPreferences> { LocalBookmarkPreferences(settingsFactory = get()) }
    single<TorPreferences> { LocalTorPreferences(settingsFactory = get()) }

    /*
     * Eager: routing has to be able to read the requested intent synchronously, and application
     * initializers run concurrently, so a value restored lazily would be read before it existed.
     */
    single<MutableRequestedTorIntent>(createdAtStart = true) {
        LocalRequestedTorIntent(torPreferences = get())
    }
    single<RequestedTorIntent> { get<MutableRequestedTorIntent>() }
    single<BackgroundPreferences> { LocalBackgroundPreferences(settingsFactory = get()) }
    single<BlockListPreferences> { LocalBlockListPreferences(encryptedPreferenceFactory = get()) }
    single<LoRaPreferences> { LocalLoRaPreferences(settingsFactory = get()) }

    single<ConnectivityRepository> { LocalConnectivityRepository(connectEventBus = get()) }
    single<TransportIdentityProvider> { SecureTransportIdentityProvider(securePrefs = get()) }
}
