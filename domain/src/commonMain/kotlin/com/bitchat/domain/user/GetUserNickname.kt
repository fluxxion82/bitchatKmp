package com.bitchat.domain.user

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.user.eventbus.UserEventBus
import com.bitchat.domain.user.model.AppUser
import com.bitchat.domain.user.model.UserEvent
import com.bitchat.domain.user.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.onStart

class GetUserNickname(
    private val userRepository: UserRepository,
    private val userEventBus: UserEventBus,
) : Usecase<Unit, Flow<String>> {

    override suspend fun invoke(param: Unit): Flow<String> = channelFlow {
        userEventBus.events()
            .onStart {
                send(getNickname())
            }
            .collect { event ->
                when (event) {
                    UserEvent.NicknameUpdated,
                    UserEvent.ProfileUpdated,
                    is UserEvent.LoginChanged -> {
                        send(getNickname())
                    }

                    else -> Unit
                }
            }
    }

    private suspend fun getNickname(): String {
        return when (val user = userRepository.getAppUser()) {
            is AppUser.ActiveAnonymous -> user.name
            AppUser.Anonymous -> "anon"
        }
    }
}
