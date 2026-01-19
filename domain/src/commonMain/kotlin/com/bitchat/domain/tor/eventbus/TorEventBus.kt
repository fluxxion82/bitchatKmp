package com.bitchat.domain.tor.eventbus

import com.bitchat.domain.tor.model.TorEvent
import kotlinx.coroutines.flow.Flow

interface TorEventBus {
    fun events(): Flow<TorEvent>
    suspend fun update(event: TorEvent)
}
