package com.bitchat.domain.chat

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.chat.eventbus.ChatEventBus
import com.bitchat.domain.chat.model.BitchatMessage
import com.bitchat.domain.chat.model.ChatEvent
import com.bitchat.domain.chat.repository.ChatRepository
import com.bitchat.domain.location.model.Channel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

@OptIn(ExperimentalCoroutinesApi::class)
class ObserveChannelMessages(
    private val chatRepository: ChatRepository,
    private val chatEventBus: ChatEventBus,
) : Usecase<Channel, Flow<List<BitchatMessage>>> {

    override suspend fun invoke(param: Channel): Flow<List<BitchatMessage>> {
        return when (param) {
            is Channel.Location -> observeGeohashChannel(param.geohash)
            Channel.Mesh -> observeMeshChannel()
            is Channel.MeshDM -> observePrivateDM(param.peerID)
            is Channel.NostrDM -> observePrivateDM(param.peerID)
            is Channel.NamedChannel -> observeNamedChannel(param.channelName)
        }
    }

    private suspend fun observeGeohashChannel(geohash: String): Flow<List<BitchatMessage>> {
        return combine(
            channelFlow {
                chatEventBus.events()
                    .onStart { send(Unit) }
                    .collect { event ->
                        if (event is ChatEvent.GeohashMessagesUpdated && event.geohash == geohash) {
                            send(Unit)
                        }
                    }
            }.map { chatRepository.getGeohashMessages(geohash) },
            chatRepository.observeMiningStatus()
        ) { messages, miningId ->
            messages.map { message ->
                message.copy(isMining = miningId != null && message.id == miningId)
            }
        }
    }

    private fun observeMeshChannel(): Flow<List<BitchatMessage>> = channelFlow {
        chatEventBus.events()
            .onStart { send(chatRepository.getMeshMessages()) }
            .collect { event ->
                if (event is ChatEvent.MeshMessagesUpdated) {
                    send(chatRepository.getMeshMessages())
                }
            }
    }

    private fun observePrivateDM(peerID: String): Flow<List<BitchatMessage>> = channelFlow {
        chatEventBus.events()
            .onStart { send(chatRepository.getPrivateChats()[peerID] ?: emptyList()) }
            .collect { event ->
                if (event is ChatEvent.PrivateChatsUpdated) {
                    send(chatRepository.getPrivateChats()[peerID] ?: emptyList())
                }
            }
    }

    private fun observeNamedChannel(channelName: String): Flow<List<BitchatMessage>> = channelFlow {
        chatEventBus.events()
            .onStart { send(chatRepository.getNamedChannelMessages(channelName)) }
            .collect { event ->
                if (event is ChatEvent.NamedChannelMessagesUpdated && event.channelName == channelName) {
                    send(chatRepository.getNamedChannelMessages(channelName))
                }
            }
    }
}
