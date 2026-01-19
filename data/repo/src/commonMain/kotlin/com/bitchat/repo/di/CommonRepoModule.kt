package com.bitchat.repo.di

import com.bitchat.cache.di.GEOHASH_ALIAS_CACHE
import com.bitchat.cache.di.GEOHASH_CONVERSATION_CACHE
import com.bitchat.cache.di.RELAY_INFO_CACHE
import com.bitchat.domain.app.repository.AppRepository
import com.bitchat.domain.chat.repository.ChatRepository
import com.bitchat.domain.initialization.AppInitializer
import com.bitchat.domain.location.repository.LocationRepository
import com.bitchat.domain.nostr.repository.NostrRepository
import com.bitchat.domain.tor.repository.TorRepository
import com.bitchat.domain.user.repository.BlockListRepository
import com.bitchat.domain.user.repository.UserRepository
import com.bitchat.nostr.RelayLogSink
import com.bitchat.repo.repositories.AppRepo
import com.bitchat.repo.repositories.BlockListRepo
import com.bitchat.repo.repositories.ChatRepo
import com.bitchat.repo.repositories.LocationRepo
import com.bitchat.repo.repositories.NostrRepo
import com.bitchat.repo.repositories.TorRepo
import com.bitchat.repo.repositories.UserRepo
import com.bitchat.repo.tor.TorRelayLogSink
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

val commonRepoModule = module {
    includes(repoModule)
    single<AppRepository> {
        AppRepo(
            coroutinesContextFacade = get(),
            appPreferences = get(),
            backgroundPreferences = get(),
            backgroundServiceController = get(),
            bluetoothConnectionService = get(),
            settingsService = get(),
        )
    }
    single<ChatRepository> {
        ChatRepo(
            coroutineScopeFacade = get(),
            coroutinesContextFacade = get(),
            mesh = get(),
            nostr = get(),
            nostrClient = get(),
            nostrPreferences = get(),
            nostrRelay = get(),
            geohashAliasCache = get(named(GEOHASH_ALIAS_CACHE)),
            geohashConversationCache = get(named(GEOHASH_CONVERSATION_CACHE)),
            channelPreferences = get(),
            userPreferences = get(),
            blockListPreferences = get(),
            participantTracker = get(),
            locationEventBus = get(),
            chatEventBus = get(),
            userRepository = get(),
            appRepository = get(),
            userEventBus = get(),
            connectEventBus = get(),
            torManager = getOrNull(),
        )
    }

    single<UserRepository> {
        UserRepo(
            coroutinesContextFacade = get(),
            userPreferences = get(),
        )
    }

    single<BlockListRepository> {
        BlockListRepo(
            coroutinesContextFacade = get(),
            blockListPreferences = get(),
        )
    }

    single<NostrRepository> {
        NostrRepo(
            coroutinesContextFacade = get(),
            nostrPreferences = get(),
        )
    }

    single<LocationRepository> {
        LocationRepo(
            nostrClient = get(),
            nostrRelay = get(),
            nostrPreferences = get(),
            geohashPreferences = get(),
            locationService = get(),
            bookmarkPrefs = get(),
            participantTracker = get(),
            geocoder = get(),
            coroutinesContextFacade = get(),
            coroutineScopeFacade = get(),
            locationEventBus = get(),
        )
    }

    single {
        NostrAppInitializer(
            nostrGeoRelayClient = get(),
            resourceReader = get(),
            relayPreferences = get(),
            coroutineScopeFacade = get(),
            relayCache = get(named(RELAY_INFO_CACHE)),
        )
    } bind AppInitializer::class
    single {
        TorAppInitializer(
            torRepository = get(),
            coroutineScopeFacade = get(),
        )
    } bind AppInitializer::class
    single {
        BluetoothMeshAppInitializer(
            bluetoothMeshService = get(),
            appRepository = get(),
            connectivityRepository = get(),
            userRepository = get(),
        )
    } bind AppInitializer::class

    single {
        TorRepo(
            torManager = get(),
            torPreferences = get(),
            coroutinesContextFacade = get(),
            coroutineScopeFacade = get(),
            torEventBus = get(),
        )
    }
    single<TorRepository> { get<TorRepo>() }
    single<RelayLogSink> {
        TorRelayLogSink(
            torRepo = get(),
        )
    }
}
