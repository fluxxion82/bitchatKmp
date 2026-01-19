package com.bitchat.repo.nostr

import com.bitchat.domain.user.model.FavoriteRelationship

class NostrRecipientResolver {
    fun resolvePeerIdForNostrPubkey(
        npubOrHex: String,
        peerIdMappings: Map<String, String>,
        favorites: Map<String, FavoriteRelationship>
    ): String? {
        peerIdMappings.entries.firstOrNull { (_, value) ->
            value.equals(npubOrHex, ignoreCase = true)
        }?.let { return it.key }

        favorites.values.firstOrNull { fav ->
            fav.peerNostrPublicKey?.equals(npubOrHex, ignoreCase = true) == true
        }?.let { return it.peerNoisePublicKeyHex }

        return null
    }
}
