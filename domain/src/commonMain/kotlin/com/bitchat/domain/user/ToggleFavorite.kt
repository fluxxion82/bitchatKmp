package com.bitchat.domain.user

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.chat.repository.ChatRepository
import com.bitchat.domain.user.eventbus.UserEventBus
import com.bitchat.domain.user.model.FavoriteRelationship
import com.bitchat.domain.user.model.UserEvent
import com.bitchat.domain.user.repository.UserRepository
import kotlin.time.Clock

class ToggleFavorite(
    private val userRepository: UserRepository,
    private val chatRepository: ChatRepository,
    private val userEventBus: UserEventBus,
) : Usecase<ToggleFavorite.Params, FavoriteRelationship> {

    data class Params(
        val peerID: String,
        val peerNickname: String,
    )

    override suspend fun invoke(param: Params): FavoriteRelationship {
        val normalizedKey = param.peerID.removePrefix("nostr_").lowercase()

        val now = Clock.System.now().toEpochMilliseconds()

        // Read and written as one step: a notification from that peer handled at the same moment
        // must not be decided on, or written over, the record as it was before this.
        val updated = userRepository.updateFavorite(normalizedKey) { existing ->
            FavoriteRelationship(
                peerNoisePublicKeyHex = normalizedKey,
                peerNostrPublicKey = existing?.peerNostrPublicKey,
                peerNickname = param.peerNickname,
                isFavorite = existing?.isFavorite != true,
                theyFavoritedUs = existing?.theyFavoritedUs ?: false,
                favoritedAt = existing?.favoritedAt ?: now,
                lastUpdated = now
            )
        }

        chatRepository.sendFavoriteNotification(param.peerID, updated.isFavorite)
        userEventBus.update(UserEvent.FavoriteStatusChanged(normalizedKey))
        return updated
    }
}
