package com.bitchat.domain.nostr.repository

import com.bitchat.domain.nostr.model.PowSettings

interface NostrRepository {
    suspend fun getPowSettings(): PowSettings
    suspend fun setPowSettings(settings: PowSettings)

    suspend fun clearData()
}
