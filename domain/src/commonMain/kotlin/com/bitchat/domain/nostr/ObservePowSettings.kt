package com.bitchat.domain.nostr

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.nostr.eventbus.NostrEventBus
import com.bitchat.domain.nostr.model.PowSettings
import com.bitchat.domain.nostr.repository.NostrRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

class ObservePowSettings(
    private val repository: NostrRepository,
    private val eventBus: NostrEventBus,
) : Usecase<Unit, Flow<PowSettings>> {

    override suspend fun invoke(param: Unit): Flow<PowSettings> {
        val saved = eventBus.events()
            .map { repository.getPowSettings() }
            .onStart { emit(repository.getPowSettings()) }
        // Mining starts and stops in NostrClient without a settings event.
        return combine(saved, repository.observeMiningStatus()) { settings, mining ->
            settings.copy(isMining = mining)
        }.distinctUntilChanged()
    }
}
