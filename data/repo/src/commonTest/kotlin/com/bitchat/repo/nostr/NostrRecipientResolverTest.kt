package com.bitchat.repo.nostr

import com.bitchat.domain.user.model.FavoriteRelationship
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NostrRecipientResolverTest {
    @Test
    fun resolvesPeerIdFromPeerIdMappingFirst() {
        val resolver = NostrRecipientResolver()
        val peerIdMappings = mapOf(
            "peer16" to "npub1aaaa",
            "peer64" to "npub1bbbb"
        )
        val favorites = emptyMap<String, FavoriteRelationship>()

        val result = resolver.resolvePeerIdForNostrPubkey(
            npubOrHex = "npub1aaaa",
            peerIdMappings = peerIdMappings,
            favorites = favorites
        )

        assertEquals("peer16", result)
    }

    @Test
    fun resolvesPeerIdFromFavoritesWhenNoMappingExists() {
        val resolver = NostrRecipientResolver()
        val peerIdMappings = emptyMap<String, String>()
        val favorites = mapOf(
            "noisehex" to FavoriteRelationship(
                peerNoisePublicKeyHex = "noisehex",
                peerNostrPublicKey = "npub1cccc",
                peerNickname = "nick",
                isFavorite = true,
                theyFavoritedUs = true,
                favoritedAt = 1L,
                lastUpdated = 1L
            )
        )

        val result = resolver.resolvePeerIdForNostrPubkey(
            npubOrHex = "npub1cccc",
            peerIdMappings = peerIdMappings,
            favorites = favorites
        )

        assertEquals("noisehex", result)
    }

    @Test
    fun returnsNullWhenNoMatch() {
        val resolver = NostrRecipientResolver()

        val result = resolver.resolvePeerIdForNostrPubkey(
            npubOrHex = "npub1missing",
            peerIdMappings = emptyMap(),
            favorites = emptyMap()
        )

        assertNull(result)
    }
}
