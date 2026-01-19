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
            getAppTheme = get(),
            getPowSettings = get(),
            userEventBus = get(),
            getJoinedChannels = get(),
            getUserNickname = get(),
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
            getTorStatus = get(),
            leaveChannel = get(),
            setUserNickname = get(),
            toggleBookmarkUseCase = get(),
            saveUserStateAction = get(),
            markPrivateChatRead = get(),
            clearAllData = get(),
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
            getUserNickname = get(),
            saveUserStateAction = get(),
            leaveChannel = get(),
            getJoinedNamedChannels = get(),
            getGeohashParticipants = get(),
            getMeshPeers = get(),
            getChannelKeyCommitment = get(),
            getAvailableNamedChannels = get(),
            getChannelMembers = get(),
            blockUser = get(),
            unblockUser = get(),
            getBlockedUsers = get(),
            joinChannel = get(),
            setChannelPassword = get(),
            clearMessages = get(),
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
            getUserState = get(),
            getUserNickname = get()
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
            resolveLocationName = get(),
            getUserState = get(),
        )
    }

    viewModel { params ->
        SettingsViewModel(
            setAppTheme = get(),
            getAppTheme = get(),
            setPowSettings = get(),
            getPowSettings = get(),
            getTorStatus = get(),
            getTorMode = get(),
            enableTor = get(),
            disableTor = get(),
            getBackgroundMode = get(),
            enableBackgroundMode = get(),
            disableBackgroundMode = get(),
            showBackgroundModeSetting = params[0],
        )
    }

    viewModel {
        LocationNotesViewModel(
            observeNotes = get(),
            sendNote = get(),
            getUserNickname = get(),
            getLocationGeohash = get(),
            resolveLocationName = get(),
        )
    }
}
