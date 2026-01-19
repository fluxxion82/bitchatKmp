package com.bitchat.domain.user

import com.bitchat.domain.app.model.ActiveState
import com.bitchat.domain.app.model.BatteryOptimizationStatus
import com.bitchat.domain.app.model.UserState
import com.bitchat.domain.app.repository.AppRepository
import com.bitchat.domain.base.Usecase
import com.bitchat.domain.chat.eventbus.ChatEventBus
import com.bitchat.domain.chat.model.ChatEvent
import com.bitchat.domain.chat.repository.ChatRepository
import com.bitchat.domain.connectivity.repository.ConnectivityRepository
import com.bitchat.domain.location.eventbus.LocationEventBus
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.location.model.LocationEvent
import com.bitchat.domain.location.repository.LocationRepository
import com.bitchat.domain.user.eventbus.UserEventBus
import com.bitchat.domain.user.model.UserEvent
import com.bitchat.domain.user.model.UserStateAction
import com.bitchat.domain.user.repository.UserRepository

class SaveUserStateAction(
    private val repository: UserRepository,
    private val appRepository: AppRepository,
    private val connectivityRepository: ConnectivityRepository,
    private val chatRepository: ChatRepository,
    private val userEventBus: UserEventBus,
    private val locationRepository: LocationRepository,
    private val locationEventBus: LocationEventBus,
    private val chatEventBus: ChatEventBus,
) : Usecase<UserStateAction, Unit> {
    override suspend fun invoke(param: UserStateAction) {
        getBlockingState()?.let { blockingState ->
            repository.setUserState(blockingState)
            userEventBus.update(UserEvent.StateChanged)
            return
        }

        val newState = when (param) {
            UserStateAction.GrantPermissions -> {
                if (!appRepository.isBatteryOptimizationSkipped()
                    && appRepository.getBatteryOptimizationStatus() == BatteryOptimizationStatus.ENABLED
                ) {
                    UserState.BatteryOptimization(BatteryOptimizationStatus.ENABLED)
                } else {
                    UserState.Active(ActiveState.Chat(Channel.Mesh))
                }
            }

            UserStateAction.HandledOptimizations -> UserState.Active(ActiveState.Chat(Channel.Mesh))
            UserStateAction.Locations -> UserState.Active(ActiveState.Locations)
            UserStateAction.Settings -> UserState.Active(ActiveState.Settings)
            is UserStateAction.LocationNotes -> {
                UserState.Active(
                    ActiveState.LocationNotes(
                        previousChannel = Channel.Mesh, // can only come from Mesh channel I think
                    )
                )
            }

            is UserStateAction.Chat -> {
                val currentChannel = (repository.getUserState() as? UserState.Active)
                    ?.activeState?.let { it as? ActiveState.Chat }?.channel

                when (param.channel) {
                    is Channel.Location -> {
                        val nickname = when (val user = repository.getAppUser()) {
                            is com.bitchat.domain.user.model.AppUser.ActiveAnonymous -> user.name.ifBlank { "anon" }
                            com.bitchat.domain.user.model.AppUser.Anonymous -> "anon"
                        }
                        locationRepository.registerSelfAsParticipant(
                            geohash = param.channel.geohash,
                            nickname = nickname,
                            isTeleported = param.isTeleport
                        )
                    }

                    Channel.Mesh,
                    is Channel.MeshDM,
                    is Channel.NostrDM,
                    is Channel.NamedChannel -> {
                        locationRepository.unregisterSelfFromCurrentGeohash()
                    }
                }

                chatRepository.setSelectedChannel(param.channel)

                UserState.Active(
                    ActiveState.Chat(
                        channel = param.channel,
                        previousChannel = currentChannel
                    )
                )
            }

            is UserStateAction.MeshDM -> {
                val currentChannel = (repository.getUserState() as? UserState.Active)
                    ?.activeState?.let { it as? ActiveState.Chat }?.channel

                val dmChannel = Channel.MeshDM(
                    peerID = param.peerID,
                    displayName = param.displayName
                )
                chatRepository.setSelectedChannel(dmChannel)

                UserState.Active(
                    ActiveState.Chat(
                        channel = dmChannel,
                        previousChannel = currentChannel
                    )
                )
            }

            is UserStateAction.NostrDM -> {
                val currentChannel = (repository.getUserState() as? UserState.Active)
                    ?.activeState?.let { it as? ActiveState.Chat }?.channel

                val peerID = param.peerID ?: param.fullPubkey?.let { "nostr_${it.take(16)}" }
                ?: error("NostrDM action must provide either peerID or fullPubkey")

                val fullPubkey = param.fullPubkey
                    ?: chatRepository.getFullPubkey(peerID)
                    ?: peerID.removePrefix("nostr_")

                val sourceGeohash = param.sourceGeohash
                    ?: chatRepository.getSourceGeohash(peerID)

                val displayName = param.displayName
                    ?: chatRepository.getDisplayName(peerID)

                val dmChannel = Channel.NostrDM(
                    peerID = peerID,
                    fullPubkey = fullPubkey,
                    sourceGeohash = sourceGeohash,
                    displayName = displayName
                )
                chatRepository.setSelectedChannel(dmChannel)

                chatRepository.storePersonDataForDM(
                    peerID = peerID,
                    fullPubkey = fullPubkey,
                    sourceGeohash = sourceGeohash,
                    displayName = displayName
                )

                UserState.Active(
                    ActiveState.Chat(
                        channel = dmChannel,
                        previousChannel = currentChannel
                    )
                )
            }
        }
        println("new user state: $newState")
        repository.setUserState(newState)
        userEventBus.update(UserEvent.StateChanged)
        if (newState is UserState.Active && newState.activeState is ActiveState.Chat) {
            locationEventBus.update(LocationEvent.ChannelChanged)
            chatEventBus.update(ChatEvent.ChannelChanged)
        }
    }

    private suspend fun getBlockingState(): UserState? {
        return when {
            !appRepository.hasRequiredPermissions() -> UserState.PermissionsRequired
            !connectivityRepository.isBluetoothEnabled() -> UserState.BluetoothDisabled
            !connectivityRepository.isLocationServicesEnabled() -> UserState.LocationServicesDisabled
            else -> null
        }
    }
}
