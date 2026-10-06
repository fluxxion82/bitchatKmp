package com.bitchat.domain.user

import com.bitchat.domain.base.defaultContextFacade
import com.bitchat.domain.chat.repository.ChatRepository
import com.bitchat.domain.user.eventbus.InMemoryUserEventBus
import com.bitchat.domain.user.model.FavoriteRelationship
import com.bitchat.domain.user.repository.UserRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToggleFavoriteTest {
    private val userRepository = mockk<UserRepository>(relaxed = true)
    private val chatRepository = mockk<ChatRepository>(relaxed = true)
    private val userEventBus = InMemoryUserEventBus(defaultContextFacade)
    private val useCase = ToggleFavorite(userRepository, chatRepository, userEventBus)

    /** The repository's one-step update, applied to [existing] as the record saved under [key]. */
    private fun saved(key: String, existing: FavoriteRelationship?) {
        coEvery { userRepository.updateFavorite(key, any()) } answers {
            secondArg<(FavoriteRelationship?) -> FavoriteRelationship>().invoke(existing)
        }
    }

    @Test
    fun `normalizes nostr-prefixed peerID when toggling favorite`() = runTest {
        saved("abc123def4567890", existing = null)

        val updated = useCase(ToggleFavorite.Params(peerID = "nostr_abc123def4567890", peerNickname = "TestUser"))

        assertEquals("abc123def4567890", updated.peerNoisePublicKeyHex)
        assertTrue(updated.isFavorite)
    }

    @Test
    fun `normalizes uppercase peerID to lowercase`() = runTest {
        saved("abc123def4567890", existing = null)

        val updated = useCase(ToggleFavorite.Params(peerID = "ABC123DEF4567890", peerNickname = "TestUser"))

        assertEquals("abc123def4567890", updated.peerNoisePublicKeyHex)
    }

    @Test
    fun `favoriting from nostr context updates existing mesh entry`() = runTest {
        val normalizedId = "abc123def4567890"
        saved(
            normalizedId,
            existing = FavoriteRelationship(
                peerNoisePublicKeyHex = normalizedId,
                peerNostrPublicKey = "npub1xyz...",
                peerNickname = "ExistingNickname",
                isFavorite = false,
                theyFavoritedUs = true,
                favoritedAt = 1000L,
                lastUpdated = 1000L
            ),
        )

        val updated = useCase(ToggleFavorite.Params(peerID = "nostr_$normalizedId", peerNickname = "NewNickname"))

        assertEquals(normalizedId, updated.peerNoisePublicKeyHex)
        assertTrue(updated.isFavorite)
        assertTrue(updated.theyFavoritedUs)
        assertEquals("npub1xyz...", updated.peerNostrPublicKey)
        assertEquals(1000L, updated.favoritedAt)
    }

    @Test
    fun `toggling a favorite again takes it back and says so to the peer`() = runTest {
        val normalizedId = "abc123def4567890"
        saved(
            normalizedId,
            existing = FavoriteRelationship(normalizedId, "npub1xyz...", "Friend", isFavorite = true, theyFavoritedUs = true, 1000L, 1000L),
        )

        val updated = useCase(ToggleFavorite.Params(peerID = normalizedId, peerNickname = "Friend"))

        assertFalse(updated.isFavorite)
        assertTrue(updated.theyFavoritedUs, "what the peer said about itself stays")
        coVerify { chatRepository.sendFavoriteNotification(normalizedId, false) }
    }

    @Test
    fun `sends notification using original peerID for routing`() = runTest {
        val nostrPrefixedId = "nostr_abc123def4567890"
        saved("abc123def4567890", existing = null)

        useCase(ToggleFavorite.Params(peerID = nostrPrefixedId, peerNickname = "TestUser"))

        coVerify { chatRepository.sendFavoriteNotification(nostrPrefixedId, true) }
    }

    @Test
    fun `reads and writes the record as one step of the repository`() = runTest {
        saved("abc123def4567890", existing = null)

        useCase(ToggleFavorite.Params(peerID = "nostr_abc123def4567890", peerNickname = "TestUser"))

        // A separate read and save could be decided on a record a notification has changed in between.
        coVerify(exactly = 1) { userRepository.updateFavorite("abc123def4567890", any()) }
        coVerify(exactly = 0) { userRepository.getFavorite(any()) }
        coVerify(exactly = 0) { userRepository.saveFavorite(any()) }
    }
}
