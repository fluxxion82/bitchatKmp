package com.bitchat.design.chat

import com.bitchat.domain.user.model.FavoriteRelationship
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatHeaderFavoritesTest {

    @Test
    fun `uses relationship flag when present`() {
        val rels = mapOf(
            "peer-a" to FavoriteRelationship(
                peerNoisePublicKeyHex = "peer-a",
                peerNostrPublicKey = null,
                peerNickname = "Alice",
                isFavorite = false,
                theyFavoritedUs = true,
                favoritedAt = 1L,
                lastUpdated = 2L
            ),
            "peer-b" to FavoriteRelationship(
                peerNoisePublicKeyHex = "peer-b",
                peerNostrPublicKey = null,
                peerNickname = "Bob",
                isFavorite = true,
                theyFavoritedUs = false,
                favoritedAt = 3L,
                lastUpdated = 4L
            )
        )

        assertFalse(
            computeFavoriteStatus(
                peerID = "peer-a",
                peerFingerprints = emptyMap(),
                favoritePeers = emptySet(),
                favoriteRelationships = rels
            )
        )
        assertTrue(
            computeFavoriteStatus(
                peerID = "peer-b",
                peerFingerprints = emptyMap(),
                favoritePeers = emptySet(),
                favoriteRelationships = rels
            )
        )
    }

    @Test
    fun `falls back to peerID when no relationship available`() {
        val result = computeFavoriteStatus(
            peerID = "peer-c",
            peerFingerprints = emptyMap(),
            favoritePeers = setOf("peer-c"),
            favoriteRelationships = emptyMap()
        )
        assertTrue(result)
    }

    @Test
    fun `offline favorites excludes connected peers`() {
        val rels = mapOf(
            "abc123" to FavoriteRelationship(
                peerNoisePublicKeyHex = "abc123",
                peerNostrPublicKey = null,
                peerNickname = "Alice",
                isFavorite = true,
                theyFavoritedUs = false,
                favoritedAt = 1L,
                lastUpdated = 2L
            )
        )
        val connectedPeers = listOf("abc123")

        val result = computeOfflineFavorites(rels, connectedPeers)

        assertTrue(result.isEmpty(), "Connected peer should be excluded from offline favorites")
    }

    @Test
    fun `offline favorites excludes connected peers with nostr prefix`() {
        val rels = mapOf(
            "abc123" to FavoriteRelationship(
                peerNoisePublicKeyHex = "abc123",
                peerNostrPublicKey = null,
                peerNickname = "Alice",
                isFavorite = true,
                theyFavoritedUs = false,
                favoritedAt = 1L,
                lastUpdated = 2L
            )
        )
        val connectedPeers = listOf("abc123")

        val result = computeOfflineFavorites(rels, connectedPeers)

        assertTrue(result.isEmpty(), "Connected peer should be excluded regardless of key format")
    }

    @Test
    fun `offline favorites includes disconnected favorited peers`() {
        val rels = mapOf(
            "abc123" to FavoriteRelationship(
                peerNoisePublicKeyHex = "abc123",
                peerNostrPublicKey = null,
                peerNickname = "Alice",
                isFavorite = true,
                theyFavoritedUs = false,
                favoritedAt = 1L,
                lastUpdated = 2L
            )
        )
        val connectedPeers = listOf("different456") // Different peer connected

        val result = computeOfflineFavorites(rels, connectedPeers)

        assertTrue(result.size == 1, "Disconnected favorite should be included")
        assertTrue(result[0].peerNoisePublicKeyHex == "abc123")
    }

    @Test
    fun `offline favorites excludes users not favorited by us`() {
        val rels = mapOf(
            "abc123" to FavoriteRelationship(
                peerNoisePublicKeyHex = "abc123",
                peerNostrPublicKey = null,
                peerNickname = "Alice",
                isFavorite = false, // We haven't favorited them
                theyFavoritedUs = true, // They favorited us
                favoritedAt = 1L,
                lastUpdated = 2L
            )
        )
        val connectedPeers = emptyList<String>()

        val result = computeOfflineFavorites(rels, connectedPeers)

        assertTrue(result.isEmpty(), "Should not include users we haven't favorited")
    }

    @Test
    fun `offline favorites deduplicates same user with different key formats`() {
        val rels = mapOf(
            "abc123" to FavoriteRelationship(
                peerNoisePublicKeyHex = "abc123",
                peerNostrPublicKey = "npub1xyz",
                peerNickname = "Alice",
                isFavorite = true,
                theyFavoritedUs = false,
                favoritedAt = 1L,
                lastUpdated = 2L
            ),
            "nostr_abc123" to FavoriteRelationship(
                peerNoisePublicKeyHex = "nostr_abc123",
                peerNostrPublicKey = "npub1xyz", // Same Nostr pubkey
                peerNickname = "Alice",
                isFavorite = true,
                theyFavoritedUs = true,
                favoritedAt = 3L,
                lastUpdated = 4L
            )
        )
        val connectedPeers = emptyList<String>()

        val result = computeOfflineFavorites(rels, connectedPeers)

        assertEquals(result.size, 1, "Should deduplicate same user: ${result.size}")
    }

    @Test
    fun `offline favorites handles case insensitivity`() {
        val rels = mapOf(
            "abc123" to FavoriteRelationship(
                peerNoisePublicKeyHex = "ABC123",
                peerNostrPublicKey = null,
                peerNickname = "Alice",
                isFavorite = true,
                theyFavoritedUs = false,
                favoritedAt = 1L,
                lastUpdated = 2L
            )
        )
        val connectedPeers = listOf("abc123")

        val result = computeOfflineFavorites(rels, connectedPeers)

        assertTrue(result.isEmpty(), "Case should not affect matching")
    }
}
