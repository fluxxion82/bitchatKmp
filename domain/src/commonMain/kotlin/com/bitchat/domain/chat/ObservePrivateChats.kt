package com.bitchat.domain.chat

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.chat.eventbus.ChatEventBus
import com.bitchat.domain.chat.model.BitchatMessage
import com.bitchat.domain.chat.model.ChatEvent
import com.bitchat.domain.chat.repository.ChatRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.onStart

class ObservePrivateChats(
    private val chatRepository: ChatRepository,
    private val chatEventBus: ChatEventBus,
) : Usecase<Unit, Flow<Map<String, List<BitchatMessage>>>> {
    override suspend fun invoke(param: Unit): Flow<Map<String, List<BitchatMessage>>> {
        return channelFlow {
            chatEventBus.events()
                .onStart {
                    send(chatRepository.getPrivateChats())
                }
                .collect { event ->
                    when (event) {
                        ChatEvent.PrivateChatsUpdated -> {
                            send(chatRepository.getPrivateChats())
                        }

                        else -> Unit
                    }
                }
        }
    }
}
