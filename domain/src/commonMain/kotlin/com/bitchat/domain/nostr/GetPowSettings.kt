package com.bitchat.domain.nostr

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.nostr.model.PowSettings
import com.bitchat.domain.nostr.repository.NostrRepository

class GetPowSettings(
    private val repository: NostrRepository,
) : Usecase<Unit, PowSettings> {

    override suspend fun invoke(param: Unit): PowSettings {
        return repository.getPowSettings()
    }
}
