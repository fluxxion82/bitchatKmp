package com.bitchat.domain.location

import com.bitchat.domain.app.model.ActiveState
import com.bitchat.domain.app.model.UserState
import com.bitchat.domain.base.Usecase
import com.bitchat.domain.chat.eventbus.ChatEventBus
import com.bitchat.domain.chat.model.ChatEvent
import com.bitchat.domain.chat.repository.ChatRepository
import com.bitchat.domain.location.eventbus.LocationEventBus
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.location.model.GeoPerson
import com.bitchat.domain.location.model.LocationEvent
import com.bitchat.domain.location.repository.LocationRepository
import com.bitchat.domain.user.eventbus.UserEventBus
import com.bitchat.domain.user.model.UserEvent
import com.bitchat.domain.user.repository.UserRepository
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlin.time.Clock

class ObserveChannelParticipants(
    private val locationRepository: LocationRepository,
    private val chatRepository: ChatRepository,
    private val locationEventBus: LocationEventBus,
    private val chatEventBus: ChatEventBus,
    private val userRepository: UserRepository,
    private val userEventBus: UserEventBus,
) : Usecase<Unit, Flow<List<GeoPerson>>> {
    override suspend fun invoke(param: Unit): Flow<List<GeoPerson>> = channelFlow {
        var currentChannel: Channel? = resolveCurrentChannel()
        sendParticipantsForChannel(currentChannel)

        suspend fun refreshChannelIfNeeded() {
            val nextChannel = resolveCurrentChannel()
            if (nextChannel != currentChannel) {
                currentChannel = nextChannel
                sendParticipantsForChannel(nextChannel)
            }
        }

        merge(
            locationEventBus.events().map { Trigger.Location(it) },
            chatEventBus.events().map { Trigger.Chat(it) },
            userEventBus.events().filter { it is UserEvent.StateChanged }.map { Trigger.UserStateChanged }
        ).collect { trigger ->
            when (trigger) {
                is Trigger.Location -> when (trigger.event) {
                    LocationEvent.ChannelChanged -> refreshChannelIfNeeded()
                    LocationEvent.ParticipantsChanged -> {
                        if (currentChannel is Channel.Location) {
                            send(locationRepository.getCurrentGeohashPeople())
                        }
                    }

                    else -> Unit
                }

                is Trigger.Chat -> when (trigger.event) {
                    ChatEvent.MeshPeersUpdated -> {
                        if (currentChannel is Channel.Mesh || currentChannel is Channel.MeshDM) {
                            send(chatRepository.getMeshPeers())
                        }
                    }

                    is ChatEvent.GeohashParticipantsChanged -> {
                        val channel = currentChannel
                        if (channel is Channel.NostrDM && channel.sourceGeohash == trigger.event.geohash) {
                            val participants = chatRepository.getGeohashParticipants(trigger.event.geohash)
                                .toGeoPeople()
                            send(participants)
                        }
                    }

                    ChatEvent.ChannelChanged -> refreshChannelIfNeeded()
                    else -> Unit
                }

                Trigger.UserStateChanged -> refreshChannelIfNeeded()
            }
        }
    }

    private suspend fun SendChannel<List<GeoPerson>>.sendParticipantsForChannel(channel: Channel?) {
        when (channel) {
            is Channel.Location -> send(locationRepository.getCurrentGeohashPeople())
            Channel.Mesh,
            is Channel.MeshDM -> send(chatRepository.getMeshPeers())

            is Channel.NostrDM -> {
                val geohash = channel.sourceGeohash
                if (!geohash.isNullOrBlank()) {
                    val participants = chatRepository.getGeohashParticipants(geohash).toGeoPeople()
                    send(participants)
                } else {
                    send(emptyList())
                }
            }

            is Channel.NamedChannel -> send(emptyList())
            null -> send(emptyList())
        }
    }

    private suspend fun resolveCurrentChannel(): Channel {
        val userState = userRepository.getUserState()
        val activeChannel = (userState as? UserState.Active)
            ?.activeState
            ?.let { it as? ActiveState.Chat }
            ?.channel
        return activeChannel ?: Channel.Mesh
    }

    private fun Map<String, String>.toGeoPeople(): List<GeoPerson> {
        val now = Clock.System.now()
        return map { (pubkey, baseName) ->
            GeoPerson(
                id = pubkey,
                displayName = baseName,
                lastSeen = now
            )
        }
    }

    private sealed interface Trigger {
        data class Location(val event: LocationEvent) : Trigger
        data class Chat(val event: ChatEvent) : Trigger
        object UserStateChanged : Trigger
    }
}
