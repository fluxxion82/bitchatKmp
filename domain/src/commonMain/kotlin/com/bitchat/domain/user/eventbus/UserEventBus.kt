package com.bitchat.domain.user.eventbus

import com.bitchat.domain.user.model.UserEvent
import kotlinx.coroutines.flow.Flow

interface UserEventBus {
    fun events(): Flow<UserEvent>
    suspend fun update(event: UserEvent)
}
