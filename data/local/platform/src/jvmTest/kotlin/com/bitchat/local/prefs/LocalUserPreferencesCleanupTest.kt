package com.bitchat.local.prefs

import com.bitchat.domain.user.model.FavoriteRelationship
import com.bitchat.local.prefs.impl.LocalUserPreferences
import com.russhwolf.settings.PropertiesSettings
import com.russhwolf.settings.Settings
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Removing what must not stay saved: many records in one write, and the names older versions kept. */
class LocalUserPreferencesCleanupTest {
    private var writes = 0
    // Kept in memory; every change to the store counts as one write.
    private val settings: Settings = PropertiesSettings(Properties()) { writes++ }
    private val preferences = LocalUserPreferences(object : EncryptionSettingsFactory {
        override fun createEncrypted(name: String): Settings = settings
    })

    @Test
    fun oneUpdateRemovesEveryKeyNamedAndSavesInASingleWrite() {
        repeat(5) { preferences.saveFavorite(favorite("key$it")) }
        writes = 0

        val seen = preferences.updateFavorites { saved ->
            FavoritesUpdate(result = saved.keys, save = listOf(favorite("new")), remove = listOf("key0", "KEY2", "key4", "never-saved"))
        }

        assertEquals(setOf("key0", "key1", "key2", "key3", "key4"), seen, "the decision is made on what was saved")
        assertEquals(setOf("key1", "key3", "new"), preferences.getAllFavorites().keys)
        assertEquals(1, writes)
    }

    @Test
    fun anUpdateThatChangesNothingWritesNothing() {
        preferences.saveFavorite(favorite("key"))
        writes = 0

        preferences.updateFavorites { FavoritesUpdate(Unit) }
        preferences.updateFavorites { FavoritesUpdate(Unit, remove = listOf("never-saved")) }
        preferences.updateFavorites { saved -> FavoritesUpdate(Unit, save = listOf(saved.getValue("key"))) }

        assertEquals(0, writes)
    }

    @Test
    fun changesMadeAtTheSameTimeAreNeverDecidedOnAStaleRecord() {
        // Eight threads each read the record, add one to a number in it and write it back, 200 times.
        // Decided on a stale read, some of those would be lost.
        preferences.saveFavorite(favorite("shared").copy(lastUpdated = 0))
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        repeat(8) {
            pool.execute {
                start.await()
                repeat(200) {
                    preferences.updateFavorites { saved ->
                        val now = saved.getValue("shared")
                        FavoritesUpdate(Unit, save = listOf(now.copy(lastUpdated = now.lastUpdated + 1)))
                    }
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))

        assertEquals(1_600, preferences.getFavorite("shared")?.lastUpdated)
    }

    @Test
    fun aFavouriteTheUserJustMadeIsNotRemovedByADecisionTakenBeforeIt() {
        // What a notification or the trim does: remove a record only if it is still not a favourite.
        preferences.saveFavorite(favorite("peer"))
        fun removeIfStillNotFavourited() = preferences.updateFavorites { saved ->
            FavoritesUpdate(Unit, remove = listOfNotNull("peer".takeIf { saved["peer"]?.isFavorite == false }))
        }

        // The user favourites it first; the removal, decided inside its own step, sees that.
        preferences.updateFavorites { saved -> FavoritesUpdate(Unit, save = listOf(saved.getValue("peer").copy(isFavorite = true))) }
        removeIfStillNotFavourited()

        assertTrue(preferences.getFavorite("peer")?.isFavorite == true)
    }

    @Test
    fun theKeyMappingsAnOlderVersionSavedAreRemovedAndNothingIsWrittenWhenThereAreNone() {
        repeat(4) { preferences.setNostrPubkeyForPeerID("peer$it", "npub$it") }
        writes = 0

        preferences.clearAllPeerIDMappings()
        assertEquals(emptyMap(), preferences.getAllPeerIDMappings())
        assertEquals(1, writes)

        preferences.clearAllPeerIDMappings()
        assertEquals(1, writes, "a normal start writes nothing")
    }

    @Test
    fun theNamesAnOlderVersionSavedAreRemovedAndNothingIsWrittenWhenThereAreNone() {
        settings.putString("peer_display_names", """{"nostr_0011223344556677":"someone"}""")
        writes = 0

        preferences.clearPeerDisplayNames()
        assertFalse(settings.hasKey("peer_display_names"))
        assertEquals(1, writes)

        preferences.clearPeerDisplayNames()
        assertEquals(1, writes, "a normal start writes nothing")
    }

    private fun favorite(key: String) = FavoriteRelationship(
        peerNoisePublicKeyHex = key,
        peerNostrPublicKey = null,
        peerNickname = key,
        isFavorite = false,
        theyFavoritedUs = true,
        favoritedAt = 1,
        lastUpdated = 1,
    )
}
