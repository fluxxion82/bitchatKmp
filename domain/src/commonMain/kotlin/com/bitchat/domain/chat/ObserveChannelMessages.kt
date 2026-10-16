package com.bitchat.domain.chat

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.chat.eventbus.ChatEventBus
import com.bitchat.domain.chat.model.BitchatMessage
import com.bitchat.domain.chat.model.ChatEvent
import com.bitchat.domain.chat.repository.ChatRepository
import com.bitchat.domain.location.model.Channel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel as EventChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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
            is Channel.Meshtastic -> if (param.nodeNum != null) {
                observePrivateDM(param.nodeNum.toString(16).padStart(8, '0'))
            } else {
                observeMeshChannel()
            }
        }
    }

    private suspend fun observeGeohashChannel(geohash: String): Flow<List<BitchatMessage>> {
        return combine(
            channelFlow {
                // Subscribed before the first read: an event published during it must not be lost to a later one.
                val events = EventChannel<ChatEvent>(EventChannel.BUFFERED)
                launch(start = CoroutineStart.UNDISPATCHED) { chatEventBus.events().collect(events::send) }

                send(Unit)
                for (event in events) {
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
        // Subscribed before the first read: an event published during it must not be lost to a later one.
        val events = EventChannel<ChatEvent>(EventChannel.BUFFERED)
        launch(start = CoroutineStart.UNDISPATCHED) { chatEventBus.events().collect(events::send) }

        send(chatRepository.getMeshMessages())
        for (event in events) {
            if (event is ChatEvent.MeshMessagesUpdated) {
                send(chatRepository.getMeshMessages())
            }
        }
    }

    private fun observePrivateDM(peerID: String): Flow<List<BitchatMessage>> = channelFlow {
        // Subscribed before the first read: an event published during it must not be lost to a later one.
        val events = EventChannel<ChatEvent>(EventChannel.BUFFERED)
        launch(start = CoroutineStart.UNDISPATCHED) { chatEventBus.events().collect(events::send) }

        send(chatRepository.getPrivateChats()[peerID] ?: emptyList())
        for (event in events) {
            if (event is ChatEvent.PrivateChatsUpdated) {
                send(chatRepository.getPrivateChats()[peerID] ?: emptyList())
            }
        }
    }

    private fun observeNamedChannel(channelName: String): Flow<List<BitchatMessage>> = channelFlow {
        // Subscribed before the first read: an event published during it must not be lost to a later one.
        val events = EventChannel<ChatEvent>(EventChannel.BUFFERED)
        launch(start = CoroutineStart.UNDISPATCHED) { chatEventBus.events().collect(events::send) }

        send(chatRepository.getNamedChannelMessages(channelName))
        for (event in events) {
            if (event is ChatEvent.NamedChannelMessagesUpdated && event.channelName == channelName) {
                send(chatRepository.getNamedChannelMessages(channelName))
            }
        }
    }
}
