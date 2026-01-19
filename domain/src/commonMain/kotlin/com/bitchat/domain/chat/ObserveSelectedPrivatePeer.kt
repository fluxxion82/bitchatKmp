package com.bitchat.domain.chat

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.chat.eventbus.ChatEventBus
import com.bitchat.domain.chat.model.ChatEvent
import com.bitchat.domain.chat.repository.ChatRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.onStart

class ObserveSelectedPrivatePeer(
    private val chatRepository: ChatRepository,
    private val chatEventBus: ChatEventBus,
) : Usecase<Unit, Flow<String?>> {
    override suspend fun invoke(param: Unit): Flow<String?> {
        return channelFlow {
            chatEventBus.events()
                .onStart {
                    send(chatRepository.getSelectedPrivatePeer())
                }
                .collect { event ->
                    when (event) {
                        ChatEvent.SelectedPrivatePeerChanged -> {
                            send(chatRepository.getSelectedPrivatePeer())
                        }

                        else -> Unit
                    }
                }
        }
    }
}
