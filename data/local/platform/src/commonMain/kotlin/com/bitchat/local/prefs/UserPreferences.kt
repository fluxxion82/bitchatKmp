package com.bitchat.local.prefs

import com.bitchat.domain.app.model.UserState
import com.bitchat.domain.user.model.AppUser
import com.bitchat.domain.user.model.FavoriteRelationship

/** What [UserPreferences.updateFavorites] is to write, and what its caller gets back. */
class FavoritesUpdate<T>(
    val result: T,
    val save: List<FavoriteRelationship> = emptyList(),
    val remove: Collection<String> = emptyList(),
)

interface UserPreferences {
    fun getAppUser(): AppUser
    fun upsertAppUser(user: AppUser)
    fun getUserState(): UserState?
    fun setUserState(state: UserState)

    fun getAllFavorites(): Map<String, FavoriteRelationship>
    fun getFavorite(noisePublicKeyHex: String): FavoriteRelationship?
    fun saveFavorite(favorite: FavoriteRelationship)
    fun deleteFavorite(noisePublicKeyHex: String)

    /**
     * Changes the saved favourites as ONE step: [change] is given them as they are now (keys in lower
     * case) and says what to save and what to remove; that is written once, or not at all when it
     * asks for nothing. No other write of the favourites can come between the read and the write, so
     * a decision is never applied to records that changed after it was made (the user's toggle, a
     * peer's notification and the start-up trim all go through here). [change] must be quick and
     * must not call back into the favourites.
     */
    fun <T> updateFavorites(change: (Map<String, FavoriteRelationship>) -> FavoritesUpdate<T>): T
    fun clearAllFavorites()
    fun getNostrPubkeyForPeerID(peerID: String): String?
    fun setNostrPubkeyForPeerID(peerID: String, nostrPubkey: String)
    fun getAllPeerIDMappings(): Map<String, String>
    /**
     * Removes the peer-id to npub mappings. The app no longer writes any: a favourite's Nostr key is
     * kept in its record. Writes only when something is saved.
     */
    fun clearAllPeerIDMappings()
    /** Removes names older versions saved from untrusted peer announcements. */
    fun clearPeerDisplayNames()

    // Last-read timestamps for private conversations
    fun getLastReadTimestamp(peerID: String): Long?
    fun setLastReadTimestamp(peerID: String, timestamp: Long)
    fun getAllLastReadTimestamps(): Map<String, Long>
}
