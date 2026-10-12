package com.bitchat.domain.nostr.eventbus

import com.bitchat.domain.nostr.model.NostrEvent
import kotlinx.coroutines.flow.Flow

interface NostrEventBus {
    fun events(): Flow<NostrEvent>
    suspend fun update(event: NostrEvent)
}
