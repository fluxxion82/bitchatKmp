package com.bitchat.domain.user.model

import kotlinx.serialization.Serializable

@Serializable
data class FavoriteRelationship(
    val peerNoisePublicKeyHex: String,
    val peerNostrPublicKey: String?,
    val peerNickname: String,
    val isFavorite: Boolean,
    val theyFavoritedUs: Boolean,
    val favoritedAt: Long,
    val lastUpdated: Long
) {
    val isMutual: Boolean get() = isFavorite && theyFavoritedUs
}
