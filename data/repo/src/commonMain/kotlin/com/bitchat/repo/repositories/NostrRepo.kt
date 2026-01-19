package com.bitchat.repo.repositories

import com.bitchat.domain.base.CoroutinesContextFacade
import com.bitchat.domain.nostr.model.PowSettings
import com.bitchat.domain.nostr.repository.NostrRepository
import com.bitchat.nostr.NostrPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class NostrRepo(
    private val coroutinesContextFacade: CoroutinesContextFacade,
    private val nostrPreferences: NostrPreferences,
) : NostrRepository {

    override suspend fun getPowSettings(): PowSettings =
        withContext(coroutinesContextFacade.io) {
            PowSettings(
                enabled = nostrPreferences.getPowEnabled(),
                difficulty = nostrPreferences.getPowDifficulty(),
                isMining = nostrPreferences.getIsMiningFlow().first()
            )
        }

    override suspend fun setPowSettings(settings: PowSettings) =
        withContext(coroutinesContextFacade.io) {
            nostrPreferences.setPowEnabled(settings.enabled)
            nostrPreferences.setPowDifficulty(settings.difficulty)
            if (settings.isMining != nostrPreferences.getIsMiningFlow().first()) {
                nostrPreferences.setIsMining(settings.isMining)
            }
        }

    override suspend fun clearData() = withContext(coroutinesContextFacade.io) {
        nostrPreferences.setPowEnabled(false)
        nostrPreferences.setPowDifficulty(12)
        nostrPreferences.setIsMining(false)
    }
}
