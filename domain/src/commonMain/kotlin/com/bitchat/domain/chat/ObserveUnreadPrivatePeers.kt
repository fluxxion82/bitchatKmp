package com.bitchat.domain.chat

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.chat.eventbus.ChatEventBus
import com.bitchat.domain.chat.model.ChatEvent
import com.bitchat.domain.chat.repository.ChatRepository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

class ObserveUnreadPrivatePeers(
    private val chatRepository: ChatRepository,
    private val chatEventBus: ChatEventBus,
) : Usecase<Unit, Flow<Set<String>>> {
    override suspend fun invoke(param: Unit): Flow<Set<String>> {
        return channelFlow {
            // Subscribed before the first read: an event published during it must not be lost to a later one.
            val events = Channel<ChatEvent>(Channel.BUFFERED)
            launch(start = CoroutineStart.UNDISPATCHED) { chatEventBus.events().collect(events::send) }

            send(chatRepository.getUnreadPrivatePeers())
            for (event in events) {
                when (event) {
                    ChatEvent.UnreadPrivatePeersUpdated -> {
                        send(chatRepository.getUnreadPrivatePeers())
                    }

                    else -> Unit
                }
            }
        }
    }
}
