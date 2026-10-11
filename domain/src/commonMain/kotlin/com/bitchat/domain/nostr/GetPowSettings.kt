package com.bitchat.domain.nostr

import com.bitchat.domain.nostr.eventbus.NostrEventBus
import com.bitchat.domain.nostr.model.PowSettings
import com.bitchat.domain.nostr.repository.NostrRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow

class GetPowSettings(
    private val repository: NostrRepository,
    private val eventBus: NostrEventBus,
) {
    suspend operator fun invoke(): Flow<PowSettings> {
        val settings = eventBus.getNostrEvent()
            .receiveAsFlow()
            .map { repository.getPowSettings() }
            .onStart { emit(repository.getPowSettings()) }
        // Mining starts/stops in NostrClient without a PowSettingsUpdated event. Both UIs need
        // those transitions immediately, including while the settings screen is closed.
        return combine(settings, repository.observeMiningStatus()) { saved, mining ->
            saved.copy(isMining = mining)
        }.distinctUntilChanged()
    }
}
