package com.bitchat.domain.user

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.user.eventbus.UserEventBus
import com.bitchat.domain.user.model.UserEvent
import com.bitchat.domain.user.repository.UserRepository

class SetUserNickname(
    private val userRepository: UserRepository,
    private val userEventBus: UserEventBus,
) : Usecase<String, Unit> {

    override suspend fun invoke(param: String) {
        userRepository.updateNickname(param)
        userEventBus.update(UserEvent.NicknameUpdated)
    }
}
