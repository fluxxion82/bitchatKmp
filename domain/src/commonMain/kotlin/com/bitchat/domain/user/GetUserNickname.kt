package com.bitchat.domain.user

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.user.model.AppUser
import com.bitchat.domain.user.repository.UserRepository

class GetUserNickname(
    private val userRepository: UserRepository,
) : Usecase<Unit, String> {

    override suspend fun invoke(param: Unit): String {
        return userRepository.getAppUser().nickname()
    }
}

internal fun AppUser.nickname(): String = when (this) {
    is AppUser.ActiveAnonymous -> name
    AppUser.Anonymous -> "anon"
}
