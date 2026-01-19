package com.bitchat.local.prefs

import com.bitchat.domain.app.model.UserState
import com.bitchat.domain.user.model.AppUser
import com.bitchat.domain.user.model.FavoriteRelationship

interface UserPreferences {
    fun getAppUser(): AppUser
    fun upsertAppUser(user: AppUser)
    fun getUserState(): UserState?
    fun setUserState(state: UserState)

    fun getAllFavorites(): Map<String, FavoriteRelationship>
    fun getFavorite(noisePublicKeyHex: String): FavoriteRelationship?
    fun saveFavorite(favorite: FavoriteRelationship)
    fun deleteFavorite(noisePublicKeyHex: String)
    fun clearAllFavorites()
    fun getNostrPubkeyForPeerID(peerID: String): String?
    fun setNostrPubkeyForPeerID(peerID: String, nostrPubkey: String)
    fun getAllPeerIDMappings(): Map<String, String>
    fun clearAllPeerIDMappings()
    fun getPeerDisplayName(peerID: String): String?
    fun setPeerDisplayName(peerID: String, displayName: String)
    fun getAllPeerDisplayNames(): Map<String, String>
    fun clearPeerDisplayNames()

    // Last-read timestamps for private conversations
    fun getLastReadTimestamp(peerID: String): Long?
    fun setLastReadTimestamp(peerID: String, timestamp: Long)
    fun getAllLastReadTimestamps(): Map<String, Long>
}
