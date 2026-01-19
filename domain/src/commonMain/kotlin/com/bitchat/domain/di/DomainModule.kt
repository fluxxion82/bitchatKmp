package com.bitchat.domain.di

import com.bitchat.domain.app.ClearAllData
import com.bitchat.domain.app.DisableBackgroundMode
import com.bitchat.domain.app.DisableBatteryOptimization
import com.bitchat.domain.app.EnableBackgroundMode
import com.bitchat.domain.app.GetAppTheme
import com.bitchat.domain.app.GetBackgroundMode
import com.bitchat.domain.app.SetAppTheme
import com.bitchat.domain.app.SkipBatteryOptimization
import com.bitchat.domain.app.eventbus.AppEventBus
import com.bitchat.domain.app.eventbus.ForegroundEventBus
import com.bitchat.domain.app.eventbus.InMemoryAppEventBus
import com.bitchat.domain.app.eventbus.InMemoryForegroundEventBus
import com.bitchat.domain.base.CoroutineScopeFacade
import com.bitchat.domain.base.CoroutinesContextFacade
import com.bitchat.domain.base.DefaultScopeFacade
import com.bitchat.domain.base.defaultContextFacade
import com.bitchat.domain.chat.ClearMessages
import com.bitchat.domain.chat.GetAvailableNamedChannels
import com.bitchat.domain.chat.GetChannelKeyCommitment
import com.bitchat.domain.chat.GetChannelMembers
import com.bitchat.domain.chat.GetGeohashParticipants
import com.bitchat.domain.chat.GetJoinedChannels
import com.bitchat.domain.chat.GetJoinedNamedChannels
import com.bitchat.domain.chat.GetMeshPeers
import com.bitchat.domain.chat.JoinChannel
import com.bitchat.domain.chat.LeaveChannel
import com.bitchat.domain.chat.MarkPrivateChatRead
import com.bitchat.domain.chat.ObserveChannelMessages
import com.bitchat.domain.chat.ObserveLatestUnreadPrivatePeer
import com.bitchat.domain.chat.ObservePeerSessionStates
import com.bitchat.domain.chat.ObservePrivateChats
import com.bitchat.domain.chat.ObserveSelectedPrivatePeer
import com.bitchat.domain.chat.ObserveUnreadPrivatePeers
import com.bitchat.domain.chat.ProcessChatCommand
import com.bitchat.domain.chat.SendMessage
import com.bitchat.domain.chat.SendPrivateMessage
import com.bitchat.domain.chat.SetChannelPassword
import com.bitchat.domain.chat.eventbus.ChatEventBus
import com.bitchat.domain.chat.eventbus.InMemoryChatEventBus
import com.bitchat.domain.initialization.AppInitializer
import com.bitchat.domain.initialization.InitializeApplication
import com.bitchat.domain.location.BeginGeohashSampling
import com.bitchat.domain.location.EndGeohashSampling
import com.bitchat.domain.location.GetAvailableChannels
import com.bitchat.domain.location.GetBookmarkNames
import com.bitchat.domain.location.GetBookmarkedChannels
import com.bitchat.domain.location.GetLocationGeohash
import com.bitchat.domain.location.GetLocationNames
import com.bitchat.domain.location.GetLocationServicesEnabled
import com.bitchat.domain.location.GetParticipantCounts
import com.bitchat.domain.location.GetPermissionState
import com.bitchat.domain.location.GetTeleportState
import com.bitchat.domain.location.ObserveChannelParticipants
import com.bitchat.domain.location.ObserveCurrentChannelBookmarkState
import com.bitchat.domain.location.ObserveHasNotes
import com.bitchat.domain.location.ObserveLocationServicesEnabled
import com.bitchat.domain.location.ObserveNotes
import com.bitchat.domain.location.ObservePermissionState
import com.bitchat.domain.location.RequestLocationPermission
import com.bitchat.domain.location.ResolveLocationName
import com.bitchat.domain.location.SendNote
import com.bitchat.domain.location.ToggleBookmark
import com.bitchat.domain.location.ToggleLocationServices
import com.bitchat.domain.location.eventbus.InMemoryLocationEventBus
import com.bitchat.domain.location.eventbus.LocationEventBus
import com.bitchat.domain.nostr.GetPowSettings
import com.bitchat.domain.nostr.SetPowSettings
import com.bitchat.domain.nostr.eventbus.InMemoryNostrEventBus
import com.bitchat.domain.nostr.eventbus.NostrEventBus
import com.bitchat.domain.tor.DisableTor
import com.bitchat.domain.tor.EnableTor
import com.bitchat.domain.tor.GetTorMode
import com.bitchat.domain.tor.GetTorStatus
import com.bitchat.domain.tor.eventbus.InMemoryTorEventBus
import com.bitchat.domain.tor.eventbus.TorEventBus
import com.bitchat.domain.user.BlockUser
import com.bitchat.domain.user.GetAllFavorites
import com.bitchat.domain.user.GetBlockedUsers
import com.bitchat.domain.user.GetUserNickname
import com.bitchat.domain.user.GetUserState
import com.bitchat.domain.user.IsUserBlocked
import com.bitchat.domain.user.SaveUserStateAction
import com.bitchat.domain.user.SetUserNickname
import com.bitchat.domain.user.ToggleFavorite
import com.bitchat.domain.user.UnblockUser
import com.bitchat.domain.user.eventbus.InMemoryUserEventBus
import com.bitchat.domain.user.eventbus.UserEventBus
import org.koin.dsl.bind
import org.koin.dsl.module

val domainModule = module {
    factory<CoroutinesContextFacade> { defaultContextFacade }
    factory<CoroutineScopeFacade> { DefaultScopeFacade(contextFacade = get()) }

    single { InMemoryAppEventBus(contextFacade = get()) } bind AppEventBus::class
    single { InMemoryNostrEventBus(contextFacade = get()) } bind NostrEventBus::class

    single { SetAppTheme(repository = get(), eventBus = get()) }
    single { GetAppTheme(repository = get(), eventBus = get()) }
    single { SkipBatteryOptimization(repository = get()) }
    single { DisableBatteryOptimization(repository = get()) }
    single {
        ClearAllData(
            chatRepository = get(),
            userRepository = get(),
            appRepository = get(),
            locationRepository = get(),
            blockListRepository = get(),
            nostrRepository = get(),
            torRepository = get(),
            userEventBus = get(),
        )
    }

    single { GetBackgroundMode(repository = get(), eventBus = get()) }
    single { EnableBackgroundMode(repository = get(), eventBus = get()) }
    single { DisableBackgroundMode(repository = get(), eventBus = get()) }

    single { GetPowSettings(repository = get(), eventBus = get()) }
    single { SetPowSettings(repository = get(), eventBus = get()) }

    single { InitializeApplication(initializers = getKoin().getAll<AppInitializer>().toSet(), contextFacade = get()) }

    single { InMemoryForegroundEventBus() } bind ForegroundEventBus::class
    single { InMemoryUserEventBus(contextFacade = get()) } bind UserEventBus::class

    single { GetUserState(userRepository = get(), appRepository = get(), connectivityRepository = get()) }
    single {
        SaveUserStateAction(
            repository = get(),
            appRepository = get(),
            connectivityRepository = get(),
            chatRepository = get(),
            locationRepository = get(),
            userEventBus = get(),
            locationEventBus = get(),
            chatEventBus = get(),
        )
    }

    single { GetUserNickname(userRepository = get(), userEventBus = get()) }

    single { SetUserNickname(userRepository = get(), userEventBus = get()) }

    single { BlockUser(blockListRepository = get()) }
    single { UnblockUser(blockListRepository = get()) }
    single { GetBlockedUsers(blockListRepository = get()) }
    single { IsUserBlocked(blockListRepository = get()) }

    single<ChatEventBus> { InMemoryChatEventBus(contextFacade = get()) }
    single<LocationEventBus> { InMemoryLocationEventBus(contextFacade = get()) }
    single<TorEventBus> { InMemoryTorEventBus(contextFacade = get()) }

    single { GetJoinedChannels(chatRepository = get(), chatEventBus = get()) }
    single { JoinChannel(chatRepository = get(), chatEventBus = get()) }
    single { SetChannelPassword(chatRepository = get(), chatEventBus = get()) }

    single { LeaveChannel(chatRepository = get(), chatEventBus = get()) }
    single { GetJoinedNamedChannels(chatRepository = get(), chatEventBus = get()) }
    single { GetGeohashParticipants(chatRepository = get()) }
    single { GetMeshPeers(chatRepository = get()) }
    single { GetChannelKeyCommitment(chatRepository = get()) }
    single { GetAvailableNamedChannels(chatRepository = get()) }
    single { GetChannelMembers(chatRepository = get()) }
    single { ClearMessages(chatRepository = get()) }

    single { ObserveChannelMessages(chatRepository = get(), chatEventBus = get()) }
    single { SendMessage(chatRepository = get()) }
    single { ProcessChatCommand() }
    single { ObservePrivateChats(chatRepository = get(), chatEventBus = get()) }
    single { ObserveUnreadPrivatePeers(chatRepository = get(), chatEventBus = get()) }
    single { ObserveLatestUnreadPrivatePeer(chatRepository = get(), chatEventBus = get()) }
    single { ObserveSelectedPrivatePeer(chatRepository = get(), chatEventBus = get()) }
    single { ObservePeerSessionStates(chatRepository = get()) }
    single { MarkPrivateChatRead(chatRepository = get()) }
    single { SendPrivateMessage(chatRepository = get()) }

    single { GetAvailableChannels(locationRepository = get()) }
    single { GetParticipantCounts(locationRepository = get(), chatRepository = get()) }
    single { GetBookmarkedChannels(locationRepository = get()) }
    single { BeginGeohashSampling(locationRepository = get()) }
    single { EndGeohashSampling(locationRepository = get()) }
    single { ToggleBookmark(locationRepository = get(), locationEventBus = get()) }
    single { ToggleLocationServices(locationRepository = get(), locationEventBus = get()) }

    single { ObserveLocationServicesEnabled(locationRepository = get(), locationEventBus = get()) }
    single { ObservePermissionState(locationRepository = get(), locationEventBus = get()) }
    single { GetPermissionState(locationRepository = get()) }
    single { RequestLocationPermission(locationRepository = get(), locationEventBus = get()) }
    single { ObserveCurrentChannelBookmarkState(locationRepository = get(), locationEventBus = get(), userRepository = get()) }
    single { ObserveHasNotes(locationRepository = get(), locationEventBus = get(), userRepository = get()) }
    single {
        ObserveChannelParticipants(
            locationRepository = get(),
            chatRepository = get(),
            locationEventBus = get(),
            chatEventBus = get(),
            userRepository = get(),
            userEventBus = get()
        )
    }

    single { GetBookmarkNames(locationRepository = get()) }
    single { GetTeleportState(locationRepository = get()) }
    single { GetLocationServicesEnabled(locationRepository = get()) }
    single { GetLocationNames(locationRepository = get()) }
    single { ResolveLocationName(locationRepository = get()) }

    single { SendNote(repository = get(), locationEventBus = get()) }
    single { ObserveNotes(repository = get(), locationEventBus = get()) }
    single { GetLocationGeohash(repository = get()) }

    single { GetTorStatus(torRepository = get(), torEventBus = get()) }
    single { GetTorMode(torRepository = get(), torEventBus = get()) }
    single { EnableTor(torRepository = get(), torEventBus = get(), coroutineScopeFacade = get()) }
    single { DisableTor(torRepository = get(), torEventBus = get()) }

    single { GetAllFavorites(userRepository = get()) }
    single { ToggleFavorite(userRepository = get(), chatRepository = get(), userEventBus = get()) }
}
