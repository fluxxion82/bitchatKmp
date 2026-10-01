package com.bitchat.nostr

import com.bitchat.transport.IdentityStoreState
import com.bitchat.transport.TransportIdentityProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The device seed behind every geohash identity: new seeds come from the CSPRNG, and a seed
 * that is already stored is used exactly as it is.
 */
class NostrDeviceSeedTest {

    @Test
    fun `a first run mints a 32-byte seed`() {
        val store = FakeIdentityStore()

        NostrClient(FakeNostrPreferences(), store).deriveIdentity(GEOHASH)

        val stored = assertNotNull(store.values[DEVICE_SEED_KEY])
        assertEquals(32, Base64.decode(stored).size)
        assertEquals(1, store.mints)
    }

    @Test
    fun `every device mints a different seed`() {
        val stores = List(8) { FakeIdentityStore() }

        val identities = stores.map { NostrClient(FakeNostrPreferences(), it).deriveIdentity(GEOHASH) }

        // A fixed or reseeded generator would hand two devices the same seed, and with it the
        // same key for every geohash.
        assertEquals(stores.size, stores.map { it.values.getValue(DEVICE_SEED_KEY) }.toSet().size)
        assertEquals(stores.size, identities.map { it.publicKeyHex }.toSet().size)
    }

    @Test
    fun `a stored seed is used as it is and never replaced`() {
        // Seeds minted before the CSPRNG fix came from kotlin.random. Replacing one would change
        // every geohash identity on the device and orphan its geohash DM threads.
        val store = FakeIdentityStore(DEVICE_SEED_KEY to STORED_SEED_BASE64)

        val identity = NostrClient(FakeNostrPreferences(), store).deriveIdentity(GEOHASH)

        // HMAC-SHA256(seed = 00..1f, "u4pruyd" || 00000000), computed outside Kotlin.
        assertEquals(
            "cf5ac3b3b7c4374def478bcb8d927209a210726928e0a6f4d9cc148f33f8077d",
            identity.privateKeyHex,
        )
        assertEquals(STORED_SEED_BASE64, store.values[DEVICE_SEED_KEY])
        assertEquals(0, store.mints)
        assertTrue(store.writes.isEmpty(), "unexpected writes: ${store.writes}")
    }

    @Test
    fun `a stored seed gives the same identity on every start`() {
        val store = FakeIdentityStore()
        val first = NostrClient(FakeNostrPreferences(), store).deriveIdentity(GEOHASH)

        // A new client has an empty identity cache, so this goes back to the store.
        val second = NostrClient(FakeNostrPreferences(), store).deriveIdentity(GEOHASH)

        // Keys, not the whole identity: NostrIdentity also carries its creation time.
        assertEquals(first.privateKeyHex, second.privateKeyHex)
        assertEquals(1, store.mints)
    }

    /**
     * Follows the [TransportIdentityProvider.loadOrMint] contract on a first run: the stored
     * value when there is one, otherwise the minted value, which is then stored.
     */
    private class FakeIdentityStore(vararg initial: Pair<String, String>) : TransportIdentityProvider {
        val values = mutableMapOf(*initial)
        val writes = mutableListOf<String>()
        var mints = 0
            private set

        override fun loadKey(key: String): String? = values[key]
        override fun saveKey(key: String, value: String) {
            writes += "save $key"
            values[key] = value
        }
        override fun hasKey(key: String) = key in values
        override fun removeKeys(vararg keys: String) {
            writes += "remove ${keys.joinToString()}"
            keys.forEach(values::remove)
        }
        override fun clearAll() {
            writes += "clearAll"
            values.clear()
        }
        override fun loadOrMint(key: String, publicFormOf: (String) -> String, mint: () -> String): String =
            values.getOrPut(key) {
                mints++
                mint()
            }
        override fun storeState() = IdentityStoreState.FIRST_RUN
    }

    private class FakeNostrPreferences : NostrPreferences {
        override fun getLastUpdateMs() = 0L
        override fun setLastUpdateMs(value: Long) = Unit
        override fun setPowEnabled(enabled: Boolean) = Unit
        override fun getPowEnabled() = false
        override fun setPowDifficulty(difficulty: Int) = Unit
        override fun getPowDifficulty() = 0
        override fun setIsMining(isMining: Boolean) = Unit
        override fun getIsMiningFlow() = MutableStateFlow(false)
    }

    private companion object {
        /** NostrClient's storage key. Renaming it would orphan every stored seed just the same. */
        const val DEVICE_SEED_KEY = "nostr_device_seed"
        const val GEOHASH = "u4pruyd"

        /** Bytes 0x00..0x1f. */
        const val STORED_SEED_BASE64 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
    }
}
