package com.bitchat.local.di

import com.bitchat.cache.di.cacheModule
import com.bitchat.domain.connectivity.repository.ConnectivityRepository
import com.bitchat.local.prefs.AppPreferences
import com.bitchat.local.prefs.BackgroundPreferences
import com.bitchat.local.prefs.BlockListPreferences
import com.bitchat.local.prefs.BookmarkPreferences
import com.bitchat.local.prefs.ChannelPreferences
import com.bitchat.local.prefs.GeohashPreferences
import com.bitchat.local.prefs.SecureIdentityPreferences
import com.bitchat.local.prefs.TorPreferences
import com.bitchat.local.prefs.UserPreferences
import com.bitchat.local.prefs.impl.LocalAppPreferences
import com.bitchat.local.prefs.impl.LocalBackgroundPreferences
import com.bitchat.local.prefs.impl.LocalBlockListPreferences
import com.bitchat.local.prefs.impl.LocalBookmarkPreferences
import com.bitchat.local.prefs.impl.LocalChannelPreferences
import com.bitchat.local.prefs.impl.LocalGeohashPreferences
import com.bitchat.local.prefs.impl.LocalNostrPreferences
import com.bitchat.local.prefs.impl.LocalSecureIdentityPreferences
import com.bitchat.local.prefs.impl.LocalTorPreferences
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
    single<SecureIdentityPreferences> { LocalSecureIdentityPreferences(encryptedPreferenceFactory = get()) }
    single<NostrPreferences> { LocalNostrPreferences(settingsFactory = get()) }
    single<GeohashPreferences> { LocalGeohashPreferences(settingsFactory = get()) }
    single<ChannelPreferences> { LocalChannelPreferences(settingsFactory = get()) }
    single<BookmarkPreferences> { LocalBookmarkPreferences(settingsFactory = get()) }
    single<TorPreferences> { LocalTorPreferences(settingsFactory = get()) }
    single<BackgroundPreferences> { LocalBackgroundPreferences(settingsFactory = get()) }
    single<BlockListPreferences> { LocalBlockListPreferences(encryptedPreferenceFactory = get()) }

    single<ConnectivityRepository> { LocalConnectivityRepository(connectEventBus = get()) }
    single<TransportIdentityProvider> { SecureTransportIdentityProvider(securePrefs = get()) }
}
