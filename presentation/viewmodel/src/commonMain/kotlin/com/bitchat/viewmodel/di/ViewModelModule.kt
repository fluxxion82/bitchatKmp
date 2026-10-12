package com.bitchat.viewmodel.di

import com.bitchat.viewmodel.battery.BatteryOptimizationViewModel
import com.bitchat.viewmodel.chat.ChatViewModel
import com.bitchat.viewmodel.chat.DmViewModel
import com.bitchat.viewmodel.location.LocationChannelsViewModel
import com.bitchat.viewmodel.location.LocationDisabledViewModel
import com.bitchat.viewmodel.location.LocationNotesViewModel
import com.bitchat.viewmodel.main.MainViewModel
import com.bitchat.viewmodel.permissions.PermissionsErrorViewModel
import com.bitchat.viewmodel.permissions.PermissionsViewModel
import com.bitchat.viewmodel.settings.SettingsViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val viewModelModule = module {
    viewModel {
        MainViewModel(
            getUserState = get(),
            observeAppTheme = get(),
            observePowSettings = get(),
            userEventBus = get(),
            observeJoinedChannels = get(),
            observeUserNickname = get(),
            observePrivateChats = get(),
            observeUnreadPrivatePeers = get(),
            observeLatestUnreadPrivatePeer = get(),
            observePeerSessionStates = get(),
            observeLocationServicesEnabled = get(),
            observePermissionState = get(),
            observeCurrentChannelBookmarkState = get(),
            observeHasNotes = get(),
            observeChannelParticipants = get(),
            getAllFavorites = get(),
            toggleFavoriteUseCase = get(),
            observeTorStatus = get(),
            leaveChannel = get(),
            setUserNickname = get(),
            toggleBookmarkUseCase = get(),
            saveUserStateAction = get(),
            markPrivateChatRead = get(),
            clearAllData = get(),
            observeMeshChannelPeople = get(),
            clearSelectedPrivatePeer = get(),
            resolveChatFallback = get(),
        )
    }
    viewModel {
        PermissionsViewModel(
            saveUserStateAction = get(),
            getPermissionState = get(),
            requestLocationPermissionUsecase = get(),
        )
    }

    viewModel { params ->
        PermissionsErrorViewModel(
            params[0],
            saveUserStateAction = get(),
        )
    }

    viewModel {
        LocationDisabledViewModel(saveUserStateAction = get())
    }

    viewModel {
        BatteryOptimizationViewModel(saveUserStateAction = get(), skipBatteryOptimization = get(), disableBatteryOptimization = get())
    }

    viewModel {
        ChatViewModel(
            observeChannelMessages = get(),
            sendMessage = get(),
            processChatCommand = get(),
            getUserState = get(),
            chatEventBus = get(),
            observeUserNickname = get(),
            saveUserStateAction = get(),
            leaveChannel = get(),
            observeJoinedNamedChannels = get(),
            getGeohashParticipants = get(),
            getMeshPeers = get(),
            findMeshPeerByName = get(),
            getChannelKeyCommitment = get(),
            getAvailableNamedChannels = get(),
            getChannelMembers = get(),
            blockUser = get(),
            unblockUser = get(),
            getBlockedUsers = get(),
            joinChannel = get(),
            setChannelPassword = get(),
            clearMessages = get(),
            resolveChatFallback = get(),
            chatNotices = get(),
        )
    }
    viewModel {
        DmViewModel(
            observePrivateChats = get(),
            observeUnreadPrivatePeers = get(),
            observeLatestUnreadPrivatePeer = get(),
            observeSelectedPrivatePeer = get(),
            markPrivateChatRead = get(),
            sendMessage = get(),
            observeUserNickname = get()
        )
    }

    viewModel {
        LocationChannelsViewModel(
            saveUserStateAction = get(),
            getAvailableChannels = get(),
            getParticipantCounts = get(),
            getBookmarkedChannels = get(),
            beginGeohashSampling = get(),
            endGeohashSampling = get(),
            toggleBookmark = get(),
            toggleLocationServices = get(),
            getBookmarkNames = get(),
            getTeleportState = get(),
            getLocationServicesEnabled = get(),
            getLocationNames = get(),
            getLastFixInfo = get(),
            observeRequestedTorMode = get(),
            resolveLocationName = get(),
            getUserState = get(),
        )
    }

    viewModel { params ->
        SettingsViewModel(
            setAppTheme = get(),
            observeAppTheme = get(),
            setPowSettings = get(),
            getPowSettings = get(),
            observeTorStatus = get(),
            observeRequestedTorMode = get(),
            enableTor = get(),
            disableTor = get(),
            getTorAvailability = get(),
            observeBackgroundMode = get(),
            enableBackgroundMode = get(),
            disableBackgroundMode = get(),
            showBackgroundModeSetting = params[0],
            getLoRaSettings = get(),
            setLoRaEnabled = get(),
            setLoRaRegion = get(),
            setLoRaTxPower = get(),
            setShowLoRaPeers = get(),
            switchLoRaProtocol = get(),
            getAvailableLoRaProtocols = get(),
            getAppInformation = get(),
        )
    }

    // The geohash is optional: without one the notes are the building the device stands in, which
    // is what the Compose apps ask for. A host with no location source passes a channel's instead.
    viewModel { (geohash: String?) ->
        LocationNotesViewModel(
            observeNotes = get(),
            sendNote = get(),
            observeUserNickname = get(),
            getLocationGeohash = get(),
            resolveLocationName = get(),
            geohash = geohash,
        )
    }
}
