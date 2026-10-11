package com.bitchat.repo.repositories

import com.bitchat.domain.base.CoroutinesContextFacade
import com.bitchat.domain.nostr.model.PowSettings
import com.bitchat.domain.nostr.repository.NostrRepository
import com.bitchat.nostr.NostrPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
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
            // isMining is a runtime projection owned by NostrClient. A settings snapshot may
            // already be stale here, so saving preferences must never stop or restart its status.
        }

    override fun observeMiningStatus(): Flow<Boolean> = nostrPreferences.getIsMiningFlow().distinctUntilChanged()

    override suspend fun clearData() = withContext(coroutinesContextFacade.io) {
        nostrPreferences.setPowEnabled(false)
        nostrPreferences.setPowDifficulty(12)
        nostrPreferences.setIsMining(false)
    }
}
