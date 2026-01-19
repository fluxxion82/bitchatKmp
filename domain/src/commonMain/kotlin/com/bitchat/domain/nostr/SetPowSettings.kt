package com.bitchat.domain.nostr

import com.bitchat.domain.nostr.eventbus.NostrEventBus
import com.bitchat.domain.nostr.model.NostrEvent
import com.bitchat.domain.nostr.model.PowSettings
import com.bitchat.domain.nostr.repository.NostrRepository

class SetPowSettings(
    private val repository: NostrRepository,
    private val eventBus: NostrEventBus,
) {
    suspend operator fun invoke(settings: PowSettings) {
        val validSettings = settings.copy(
            difficulty = settings.difficulty.coerceIn(0, 32)
        )
        repository.setPowSettings(validSettings)
        eventBus.update(NostrEvent.PowSettingsUpdated)
    }
}
