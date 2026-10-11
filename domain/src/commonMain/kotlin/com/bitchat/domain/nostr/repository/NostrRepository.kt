package com.bitchat.domain.nostr.repository

import com.bitchat.domain.nostr.model.PowSettings
import kotlinx.coroutines.flow.Flow

interface NostrRepository {
    suspend fun getPowSettings(): PowSettings
    suspend fun setPowSettings(settings: PowSettings)

    /** Runtime mining state changes independently of the user's saved PoW settings. */
    fun observeMiningStatus(): Flow<Boolean>

    suspend fun clearData()
}
