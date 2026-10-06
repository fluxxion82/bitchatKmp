package com.bitchat.domain.user.repository

import com.bitchat.domain.app.model.UserState
import com.bitchat.domain.user.model.AppUser
import com.bitchat.domain.user.model.FavoriteRelationship
import com.bitchat.domain.user.model.Profile

interface UserRepository {
    suspend fun getAppUser(): AppUser
    suspend fun getUserState(): UserState?
    suspend fun setUserState(state: UserState)

    suspend fun getAppUserProfile(): Profile
    suspend fun updateAppUserProfile(profile: Profile)

    suspend fun updateNickname(nickname: String)

    suspend fun getAllFavorites(): Map<String, FavoriteRelationship>
    suspend fun getFavorite(noisePublicKeyHex: String): FavoriteRelationship?
    suspend fun getFavoriteByNoiseKey(noisePublicKeyBytes: ByteArray): FavoriteRelationship?
    suspend fun saveFavorite(favorite: FavoriteRelationship)

    /**
     * Changes the favourite saved under [noisePublicKeyHex] as one step: [change] is given the record
     * as it is now (null when there is none) and returns the record to save, which is also returned.
     * Nothing else can change the favourites between the read and the write, so a change decided on
     * what was read is never written over something newer.
     */
    suspend fun updateFavorite(
        noisePublicKeyHex: String,
        change: (FavoriteRelationship?) -> FavoriteRelationship,
    ): FavoriteRelationship
    suspend fun deleteFavorite(noisePublicKeyHex: String)
    suspend fun clearAllFavorites()
    suspend fun getMutualFavorites(): List<FavoriteRelationship>
    suspend fun getOurFavorites(): List<FavoriteRelationship>

    suspend fun getNostrPubkeyForPeerID(peerID: String): String?
    suspend fun setNostrPubkeyForPeerID(peerID: String, nostrPubkey: String)
    suspend fun findPeerIDForNostrPubkey(nostrPubkey: String): String?
    suspend fun findNostrPubkeyByNoiseKey(noisePublicKeyBytes: ByteArray): String?
    suspend fun findNoiseKeyByNostrPubkey(nostrPubkey: String): ByteArray?
    suspend fun clearAllPeerIDMappings()

    suspend fun clearData()
}
