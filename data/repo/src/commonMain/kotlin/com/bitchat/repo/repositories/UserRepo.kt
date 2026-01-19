package com.bitchat.repo.repositories

import com.bitchat.domain.app.model.UserState
import com.bitchat.domain.base.CoroutinesContextFacade
import com.bitchat.domain.user.model.AppUser
import com.bitchat.domain.user.model.FavoriteRelationship
import com.bitchat.domain.user.model.Profile
import com.bitchat.domain.user.repository.UserRepository
import com.bitchat.local.prefs.UserPreferences
import com.bitchat.nostr.util.fromNoiseKeyHex
import kotlinx.coroutines.withContext

class UserRepo(
    private val coroutinesContextFacade: CoroutinesContextFacade,
    private val userPreferences: UserPreferences,
) : UserRepository {
    var profile: Profile = Profile(
        id = "1",
        username = "",
    )

    override suspend fun getAppUser(): AppUser = withContext(coroutinesContextFacade.io) {
        userPreferences.getAppUser()
    }

    override suspend fun getUserState(): UserState? = withContext(coroutinesContextFacade.io) {
        userPreferences.getUserState()
    }

    override suspend fun setUserState(state: UserState) = withContext(coroutinesContextFacade.io) {
        userPreferences.setUserState(state)
    }

    override suspend fun getAppUserProfile(): Profile = withContext(coroutinesContextFacade.io) {
        profile
    }

    override suspend fun updateAppUserProfile(profile: Profile) = withContext(coroutinesContextFacade.io) {
        this@UserRepo.profile = profile
    }

    override suspend fun updateNickname(nickname: String) = withContext(coroutinesContextFacade.io) {
        val currentUser = userPreferences.getAppUser()
        val updatedUser = when (currentUser) {
            is AppUser.ActiveAnonymous -> AppUser.ActiveAnonymous(nickname)
            AppUser.Anonymous -> AppUser.ActiveAnonymous(nickname)
        }
        userPreferences.upsertAppUser(updatedUser)
    }

    override suspend fun getAllFavorites(): Map<String, FavoriteRelationship> = withContext(coroutinesContextFacade.io) {
        userPreferences.getAllFavorites()
    }

    override suspend fun getFavorite(noisePublicKeyHex: String): FavoriteRelationship? = withContext(coroutinesContextFacade.io) {
        userPreferences.getFavorite(noisePublicKeyHex)
    }

    override suspend fun getFavoriteByNoiseKey(noisePublicKeyBytes: ByteArray): FavoriteRelationship? =
        withContext(coroutinesContextFacade.io) {
            val keyHex = noisePublicKeyBytes.toHexString()
            userPreferences.getFavorite(keyHex)
        }

    override suspend fun saveFavorite(favorite: FavoriteRelationship) = withContext(coroutinesContextFacade.io) {
        userPreferences.saveFavorite(favorite)
    }

    override suspend fun deleteFavorite(noisePublicKeyHex: String) = withContext(coroutinesContextFacade.io) {
        userPreferences.deleteFavorite(noisePublicKeyHex)
    }

    override suspend fun clearAllFavorites() = withContext(coroutinesContextFacade.io) {
        userPreferences.clearAllFavorites()
    }

    override suspend fun getMutualFavorites(): List<FavoriteRelationship> = withContext(coroutinesContextFacade.io) {
        getAllFavorites().values.filter { it.isMutual }
    }

    override suspend fun getOurFavorites(): List<FavoriteRelationship> = withContext(coroutinesContextFacade.io) {
        getAllFavorites().values.filter { it.isFavorite }
    }

    override suspend fun getNostrPubkeyForPeerID(peerID: String): String? = withContext(coroutinesContextFacade.io) {
        userPreferences.getNostrPubkeyForPeerID(peerID)
    }

    override suspend fun setNostrPubkeyForPeerID(peerID: String, nostrPubkey: String) = withContext(coroutinesContextFacade.io) {
        userPreferences.setNostrPubkeyForPeerID(peerID, nostrPubkey)
    }

    override suspend fun findPeerIDForNostrPubkey(nostrPubkey: String): String? = withContext(coroutinesContextFacade.io) {
        val mappings = userPreferences.getAllPeerIDMappings()
        mappings.entries.firstOrNull { entry ->
            entry.value.equals(nostrPubkey, ignoreCase = true)
        }?.key?.let { return@withContext it }

        val favorites = getAllFavorites()
        favorites.values.firstOrNull { rel ->
            rel.peerNostrPublicKey?.equals(nostrPubkey, ignoreCase = true) == true
        }?.let { rel ->
            return@withContext rel.peerNoisePublicKeyHex.take(16)
        }

        null
    }

    override suspend fun findNostrPubkeyByNoiseKey(noisePublicKeyBytes: ByteArray): String? = withContext(coroutinesContextFacade.io) {
        val keyHex = noisePublicKeyBytes.toHexString()
        getFavorite(keyHex)?.peerNostrPublicKey
    }

    override suspend fun findNoiseKeyByNostrPubkey(nostrPubkey: String): ByteArray? = withContext(coroutinesContextFacade.io) {
        val favorites = getAllFavorites()
        favorites.values.firstOrNull { rel ->
            rel.peerNostrPublicKey?.equals(nostrPubkey, ignoreCase = true) == true
        }?.peerNoisePublicKeyHex?.fromNoiseKeyHex()
    }

    override suspend fun clearAllPeerIDMappings() = withContext(coroutinesContextFacade.io) {
        userPreferences.clearAllPeerIDMappings()
    }

    override suspend fun clearData() = withContext(coroutinesContextFacade.io) {
        val newNickname = "anon${kotlin.random.Random.nextInt(1000, 9999)}"
        updateNickname(newNickname)
        clearAllFavorites()
        clearAllPeerIDMappings()
        userPreferences.clearPeerDisplayNames()
    }
}
