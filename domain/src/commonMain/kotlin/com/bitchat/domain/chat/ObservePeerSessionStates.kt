package com.bitchat.domain.chat

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.chat.repository.ChatRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class ObservePeerSessionStates(
    private val chatRepository: ChatRepository,
) : Usecase<Unit, Flow<Map<String, String>>> {
    override suspend fun invoke(param: Unit): Flow<Map<String, String>> = flow {
        while (true) {
            emit(chatRepository.getPeerSessionStates())
            delay(1_000)
        }
    }
}
