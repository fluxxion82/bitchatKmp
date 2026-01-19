package com.bitchat.domain.user

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.user.model.FavoriteRelationship
import com.bitchat.domain.user.repository.UserRepository

class GetAllFavorites(
    private val userRepository: UserRepository,
) : Usecase<Unit, Map<String, FavoriteRelationship>> {
    override suspend fun invoke(param: Unit): Map<String, FavoriteRelationship> {
        return userRepository.getAllFavorites()
    }
}
