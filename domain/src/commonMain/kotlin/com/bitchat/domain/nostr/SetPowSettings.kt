package com.bitchat.domain.nostr

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.nostr.eventbus.NostrEventBus
import com.bitchat.domain.nostr.model.NostrEvent
import com.bitchat.domain.nostr.model.PowSettings
import com.bitchat.domain.nostr.repository.NostrRepository

class SetPowSettings(
    private val repository: NostrRepository,
    private val eventBus: NostrEventBus,
) : Usecase<PowSettings, Unit> {

    override suspend fun invoke(param: PowSettings) {
        val validSettings = param.copy(
            difficulty = param.difficulty.coerceIn(0, 32)
        )
        repository.setPowSettings(validSettings)
        eventBus.update(NostrEvent.PowSettingsUpdated)
    }
}
