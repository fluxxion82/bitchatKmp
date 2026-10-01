package com.bitchat.nostr

import com.bitchat.transport.IdentityRefusedException
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val SEED_KEY = "nostr_device_seed"
private const val GEOHASH = "u4pruyd"

/**
 * The Nostr secrets this module creates are bytes the platform CSPRNG produced.
 *
 * End to end, through the real `NostrClient` and `Cryptography`: a recording generator is made
 * the platform CSPRNG (see [withInstalledCsprng]) and each secret must be one of its draws. The
 * device seed was minted with `kotlin.random.Random` while every shape check passed; this is the
 * test that would have failed.
 */
class NostrSecretProvenanceTest {

    @Test
    fun `the device seed is bytes the platform CSPRNG produced`() {
        withInstalledCsprng { csprng ->
            val store = FakeIdentityStore()

            NostrClient(FakeNostrPreferences(), store).deriveIdentity(GEOHASH)

            val seed = Base64.decode(store.values.getValue(SEED_KEY))
            assertEquals(32, seed.size)
            assertTrue(
                csprng.draws.any { it.contentEquals(seed) },
                "the device seed is not among the bytes the platform CSPRNG produced, so it came " +
                    "from some other generator; mint it with Cryptography.secureRandomBytes",
            )
        }
    }

    @Test
    fun `the Nostr private key is bytes the platform CSPRNG produced`() {
        withInstalledCsprng { csprng ->
            val store = FakeIdentityStore()

            val identity = assertNotNull(NostrClient(FakeNostrPreferences(), store).getCurrentNostrIdentity())

            assertEquals(identity.privateKeyHex, store.values["nostr_private_key"])
            assertTrue(
                csprng.draws.any { it.toHexString() == identity.privateKeyHex },
                "the Nostr private key is not among the bytes the platform CSPRNG produced",
            )
        }
    }

    @Test
    fun `a failing platform CSPRNG yields no seed and no identity`() {
        val store = FakeIdentityStore()

        withInstalledCsprng(fill = { throw IllegalStateException("entropy source unavailable") }) {
            val failure = assertFailsWith<IllegalStateException> {
                NostrClient(FakeNostrPreferences(), store).deriveIdentity(GEOHASH)
            }
            assertEquals("entropy source unavailable", failure.message)
        }
        assertFalse(SEED_KEY in store.values, "a failed mint must leave nothing behind")

        // Nothing is poisoned: with the platform's generator back, the next attempt mints.
        NostrClient(FakeNostrPreferences(), store).deriveIdentity(GEOHASH)
        assertEquals(32, Base64.decode(store.values.getValue(SEED_KEY)).size)
    }

    @Test
    fun `a platform CSPRNG that returns zeros never gets its seed stored`() {
        val store = FakeIdentityStore()

        withInstalledCsprng(fill = { it.fill(0) }) {
            val failure = assertFailsWith<IdentityRefusedException> {
                NostrClient(FakeNostrPreferences(), store).deriveIdentity(GEOHASH)
            }
            assertTrue(failure.message.orEmpty().contains("all zero"), failure.message)
        }
        assertFalse(SEED_KEY in store.values, "a seed the client would refuse to use was stored")
    }
}
