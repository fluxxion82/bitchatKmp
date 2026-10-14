package com.bitchat.domain.user

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.user.eventbus.UserEventBus
import com.bitchat.domain.user.model.FavoriteRelationship
import com.bitchat.domain.user.model.UserEvent
import com.bitchat.domain.user.repository.UserRepository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

class ObserveFavorites(
    private val userRepository: UserRepository,
    private val userEventBus: UserEventBus,
) : Usecase<Unit, Flow<Map<String, FavoriteRelationship>>> {

    override suspend fun invoke(param: Unit): Flow<Map<String, FavoriteRelationship>> = channelFlow {
        // Subscribed before the first read: an event published during it must not be lost to a later one.
        val events = Channel<UserEvent>(Channel.BUFFERED)
        launch(start = CoroutineStart.UNDISPATCHED) { userEventBus.events().collect(events::send) }

        send(userRepository.getAllFavorites())
        for (event in events) {
            when (event) {
                is UserEvent.FavoriteStatusChanged -> send(userRepository.getAllFavorites())
                else -> Unit
            }
        }
    }
}
