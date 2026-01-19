package com.bitchat.domain.user

import com.bitchat.domain.base.defaultContextFacade
import com.bitchat.domain.chat.repository.ChatRepository
import com.bitchat.domain.user.eventbus.InMemoryUserEventBus
import com.bitchat.domain.user.model.FavoriteRelationship
import com.bitchat.domain.user.repository.UserRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToggleFavoriteTest {
    private val userRepository = mockk<UserRepository>(relaxed = true)
    private val chatRepository = mockk<ChatRepository>(relaxed = true)
    private val userEventBus = InMemoryUserEventBus(defaultContextFacade)
    private val useCase = ToggleFavorite(userRepository, chatRepository, userEventBus)

    @Test
    fun `normalizes nostr-prefixed peerID when toggling favorite`() = runTest {
        val nostrPrefixedId = "nostr_abc123def4567890"
        val expectedNormalizedId = "abc123def4567890"

        coEvery { userRepository.getFavorite(expectedNormalizedId) } returns null

        val savedSlot = slot<FavoriteRelationship>()
        coEvery { userRepository.saveFavorite(capture(savedSlot)) } returns Unit

        useCase(ToggleFavorite.Params(peerID = nostrPrefixedId, peerNickname = "TestUser"))

        assertEquals(expectedNormalizedId, savedSlot.captured.peerNoisePublicKeyHex)
    }

    @Test
    fun `normalizes uppercase peerID to lowercase`() = runTest {
        val uppercaseId = "ABC123DEF4567890"
        val expectedNormalizedId = "abc123def4567890"

        coEvery { userRepository.getFavorite(expectedNormalizedId) } returns null

        val savedSlot = slot<FavoriteRelationship>()
        coEvery { userRepository.saveFavorite(capture(savedSlot)) } returns Unit

        useCase(ToggleFavorite.Params(peerID = uppercaseId, peerNickname = "TestUser"))

        assertEquals(expectedNormalizedId, savedSlot.captured.peerNoisePublicKeyHex)
    }

    @Test
    fun `favoriting from nostr context updates existing mesh entry`() = runTest {
        val nostrPrefixedId = "nostr_abc123def4567890"
        val normalizedId = "abc123def4567890"

        val existingEntry = FavoriteRelationship(
            peerNoisePublicKeyHex = normalizedId,
            peerNostrPublicKey = "npub1xyz...",
            peerNickname = "ExistingNickname",
            isFavorite = false,
            theyFavoritedUs = true,
            favoritedAt = 1000L,
            lastUpdated = 1000L
        )
        coEvery { userRepository.getFavorite(normalizedId) } returns existingEntry

        val savedSlot = slot<FavoriteRelationship>()
        coEvery { userRepository.saveFavorite(capture(savedSlot)) } returns Unit

        useCase(ToggleFavorite.Params(peerID = nostrPrefixedId, peerNickname = "NewNickname"))

        val saved = savedSlot.captured
        assertEquals(normalizedId, saved.peerNoisePublicKeyHex)
        assertTrue(saved.isFavorite)
        assertTrue(saved.theyFavoritedUs)
        assertEquals("npub1xyz...", saved.peerNostrPublicKey)
    }

    @Test
    fun `sends notification using original peerID for routing`() = runTest {
        val nostrPrefixedId = "nostr_abc123def4567890"

        coEvery { userRepository.getFavorite(any()) } returns null

        useCase(ToggleFavorite.Params(peerID = nostrPrefixedId, peerNickname = "TestUser"))

        coVerify { chatRepository.sendFavoriteNotification(nostrPrefixedId, true) }
    }

    @Test
    fun `looks up favorite using normalized key`() = runTest {
        val nostrPrefixedId = "nostr_abc123def4567890"
        val normalizedId = "abc123def4567890"

        coEvery { userRepository.getFavorite(normalizedId) } returns null

        useCase(ToggleFavorite.Params(peerID = nostrPrefixedId, peerNickname = "TestUser"))

        coVerify { userRepository.getFavorite(normalizedId) }
    }
}
