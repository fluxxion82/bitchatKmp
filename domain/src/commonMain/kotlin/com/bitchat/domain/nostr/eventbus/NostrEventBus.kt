package com.bitchat.domain.nostr.eventbus

import com.bitchat.domain.nostr.model.NostrEvent
import kotlinx.coroutines.channels.ReceiveChannel

interface NostrEventBus {
    fun getNostrEvent(): ReceiveChannel<NostrEvent>
    suspend fun update(event: NostrEvent)
}
