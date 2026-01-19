package com.bitchat.domain.location.eventbus

import com.bitchat.domain.location.model.LocationEvent
import kotlinx.coroutines.flow.Flow

interface LocationEventBus {
    fun events(): Flow<LocationEvent>
    suspend fun update(event: LocationEvent)
}
