package com.bitchat.domain.nostr

import com.bitchat.domain.nostr.eventbus.NostrEventBus
import com.bitchat.domain.nostr.model.PowSettings
import com.bitchat.domain.nostr.repository.NostrRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow

class GetPowSettings(
    private val repository: NostrRepository,
    private val eventBus: NostrEventBus,
) {
    suspend operator fun invoke(): Flow<PowSettings> = channelFlow {
        eventBus.getNostrEvent()
            .receiveAsFlow()
            .onStart { send(repository.getPowSettings()) }
            .map { repository.getPowSettings() }
            .collect {
                send(it)
            }
    }
}
