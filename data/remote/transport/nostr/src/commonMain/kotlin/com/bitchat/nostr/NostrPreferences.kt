package com.bitchat.nostr

import kotlinx.coroutines.flow.Flow

interface NostrPreferences {
    fun getLastUpdateMs(): Long
    fun setLastUpdateMs(value: Long)

    fun setPowEnabled(enabled: Boolean)
    fun getPowEnabled(): Boolean
    fun setPowDifficulty(difficulty: Int)
    fun getPowDifficulty(): Int
    fun setIsMining(isMining: Boolean)
    fun getIsMiningFlow(): Flow<Boolean>
}
