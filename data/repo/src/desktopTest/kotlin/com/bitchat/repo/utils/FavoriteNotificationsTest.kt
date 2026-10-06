package com.bitchat.repo.utils

import com.bitchat.domain.user.model.FavoriteRelationship
import com.bitchat.nostr.Bech32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FavoriteNotificationsTest {
    @Test
    fun parseAcceptsWhitespaceAndRejectsOtherText() {
        assertEquals("npub1typed", FavoriteNotification.parse(" [FAVORITED]: npub1typed ")?.claimedNpub)
        assertNull(FavoriteNotification.parse("[FAVORITED] npub1typed"))
    }

    @Test
    fun nostrUsesAuthenticatedKeyInsteadOfClaimedNpub() {
        val senderNpub = npub(1)
        val saved = applyFavoriteNotification(
            favorites = mapOf("a" to relationship("a", true, peerNpub = null)),
            notification = FavoriteNotification(true, "npub1typed"),
            senderKey = "a",
            senderNpub = senderNpub,
            senderName = "peer",
            now = 2,
        ).saved

        assertEquals(senderNpub, saved?.peerNostrPublicKey)
        assertTrue(saved?.theyFavoritedUs == true)
    }

    @Test
    fun nostrDoesNotTouchRecordWithDifferentStoredKey() {
        val change = applyFavoriteNotification(
            favorites = mapOf("a" to relationship("a", true, peerNpub = npub(1))),
            notification = FavoriteNotification(true, null),
            senderKey = "a",
            senderNpub = npub(2),
            senderName = "peer",
            now = 2,
        )

        assertNull(change.saved)
        assertTrue(change.removedKeys.isEmpty())
    }

    @Test
    fun nostrMatchesAnExistingMeshFavoriteByNpub() {
        val senderNpub = npub(3)
        val change = applyFavoriteNotification(
            favorites = mapOf("mesh" to relationship("mesh", true, peerNpub = senderNpub)),
            notification = FavoriteNotification(true, null),
            senderKey = "nostr-key",
            senderNpub = senderNpub,
            senderName = "peer",
            now = 2,
        )

        assertEquals("mesh", change.saved?.peerNoisePublicKeyHex)
    }

    @Test
    fun meshKeepsStoredNpubWhenClaimIsInvalid() {
        val saved = applyFavoriteNotification(
            favorites = mapOf("mesh" to relationship("mesh", false, peerNpub = npub(4))),
            notification = FavoriteNotification(true, "invalid"),
            senderKey = "mesh",
            senderNpub = null,
            senderName = "peer",
            now = 2,
        ).saved

        assertEquals(npub(4), saved?.peerNostrPublicKey)
    }

    @Test
    fun meshUnfavoriteOfUnknownPeerDoesNothing() {
        val change = applyFavoriteNotification(emptyMap(), FavoriteNotification(false, null), "mesh", null, "peer", 1)
        assertNull(change.saved)
        assertTrue(change.removedKeys.isEmpty())
    }

    @Test
    fun removesRelationshipWhenNeitherSideFavourites() {
        val change = applyFavoriteNotification(
            mapOf("mesh" to relationship("mesh", false, theyFavoritedUs = true)),
            FavoriteNotification(false, null), "mesh", null, "peer", 2,
        )
        assertNull(change.saved)
        assertEquals(listOf("mesh"), change.removedKeys)
    }

    @Test
    fun nostrFromAKeyWithNoRecordChangesNothing() {
        val change = applyFavoriteNotification(
            favorites = mapOf("friend" to relationship("friend", true, peerNpub = npub(1))),
            notification = FavoriteNotification(true, npub(1)),
            senderKey = "stranger",
            senderNpub = npub(2),
            senderName = "x",
            now = 5,
        )

        assertNull(change.saved)
        assertEquals(emptyList(), change.removedKeys)
    }

    @Test
    fun nostrPrefersTheRecordTheUserFavouritedWhenTwoClaimTheSameKey() {
        val senderNpub = npub(7)
        val change = applyFavoriteNotification(
            // A mesh peer claimed the friend's Nostr key for its own record, and was recorded first.
            favorites = linkedMapOf(
                "squatter" to relationship("squatter", false, theyFavoritedUs = true, peerNpub = senderNpub),
                "friend" to relationship("friend", true, peerNpub = senderNpub),
            ),
            notification = FavoriteNotification(true, null),
            senderKey = "0000000000000007",
            senderNpub = senderNpub,
            senderName = "x",
            now = 5,
        )

        assertEquals("friend", change.saved?.peerNoisePublicKeyHex)
        assertTrue(change.saved?.isMutual == true)
    }

    @Test
    fun nostrPrefersTheUsersFavouriteOverARecordAnOlderVersionSavedUnderTheSendersKey() {
        val senderNpub = npub(7)
        val saved = linkedMapOf(
            // Saved by an older version for anyone who sent a notification: under the Nostr key itself.
            "0000000000000007" to relationship("0000000000000007", false, theyFavoritedUs = true, peerNpub = senderNpub),
            // The same person's mesh record, which the user favourited.
            "meshfriend" to relationship("meshfriend", true, theyFavoritedUs = true, peerNpub = senderNpub),
        )

        val change = applyFavoriteNotification(saved, FavoriteNotification(false, null), "0000000000000007", senderNpub, "x", 5)

        assertEquals("meshfriend", change.saved?.peerNoisePublicKeyHex)
        assertTrue(change.saved?.isFavorite == true && change.saved?.theyFavoritedUs == false, "no longer mutual")
        assertEquals(emptyList(), change.removedKeys)
    }

    @Test
    fun aNotificationThatChangesNothingIsNotSaved() {
        val known = mapOf("peer" to relationship("peer", false, theyFavoritedUs = true, peerNpub = npub(4), lastUpdated = 1))

        val overMesh = applyFavoriteNotification(known, FavoriteNotification(true, npub(4)), "peer", null, "another name", now = 9)
        val overNostr = applyFavoriteNotification(known, FavoriteNotification(true, "npub1typed"), "peer", npub(4), "x", now = 9)

        assertNull(overMesh.saved)
        assertNull(overNostr.saved)
        assertEquals(emptyList(), overMesh.removedKeys + overNostr.removedKeys)
    }

    @Test
    fun meshRecordsAnUnknownPeerThatFavouritedTheUserWithItsNameAndAWellFormedKeyOnly() {
        val valid = applyFavoriteNotification(emptyMap(), FavoriteNotification(true, npub(3)), "PEER", null, "alice", 9).saved
        assertEquals(relationship("peer", false, theyFavoritedUs = true, peerNpub = npub(3), lastUpdated = 9).copy(peerNickname = "alice", favoritedAt = 9), valid)

        val invalid = applyFavoriteNotification(emptyMap(), FavoriteNotification(true, "npub1nonsense"), "peer", null, "alice", 9).saved
        assertNull(invalid?.peerNostrPublicKey)
        assertTrue(invalid?.theyFavoritedUs == true)
    }

    @Test
    fun meshNeverChangesTheUsersOwnChoiceOrTheSavedName() {
        val saved = applyFavoriteNotification(
            favorites = mapOf("peer" to relationship("peer", true, peerNpub = npub(1)).copy(peerNickname = "my name for them")),
            notification = FavoriteNotification(true, npub(2)),
            senderKey = "peer",
            senderNpub = null,
            senderName = "what they call themselves",
            now = 5,
        ).saved

        assertTrue(saved?.isFavorite == true && saved.theyFavoritedUs)
        assertEquals("my name for them", saved?.peerNickname)
        assertEquals(npub(2), saved?.peerNostrPublicKey, "its own record, its own key to state")
    }

    @Test
    fun capDropsOldestIncomingOnlyRecordButKeepsSavedAndUserFavorite() {
        val change = applyFavoriteNotification(
            mapOf(
                "old" to relationship("old", false, lastUpdated = 1),
                "mine" to relationship("mine", true, lastUpdated = 0),
                "new" to relationship("new", false, lastUpdated = 2),
            ),
            FavoriteNotification(true, null), "new", null, "peer", 3, maxNotFavourited = 1,
        )
        assertFalse("new" in change.removedKeys)
        assertFalse("mine" in change.removedKeys)
        assertEquals(listOf("old"), change.removedKeys)
    }

    @Test
    fun theRecordJustSavedIsKeptEvenWhenThisDevicesClockWentBack() {
        // The boards have no battery clock: "now" can be earlier than what was recorded before.
        val change = applyFavoriteNotification(
            mapOf(
                "first" to relationship("first", false, theyFavoritedUs = true, lastUpdated = 500),
                "second" to relationship("second", false, theyFavoritedUs = true, lastUpdated = 600),
            ),
            FavoriteNotification(true, null), "newcomer", null, "peer", now = 3, maxNotFavourited = 2,
        )

        assertEquals("newcomer", change.saved?.peerNoisePublicKeyHex)
        assertEquals(listOf("first"), change.removedKeys)
    }

    private fun relationship(
        key: String,
        isFavorite: Boolean,
        theyFavoritedUs: Boolean = false,
        peerNpub: String? = null,
        lastUpdated: Long = 1,
    ) = FavoriteRelationship(key, peerNpub, key, isFavorite, theyFavoritedUs, 1, lastUpdated)

    private fun npub(seed: Int): String = Bech32.encode("npub", ByteArray(32) { seed.toByte() })
}
