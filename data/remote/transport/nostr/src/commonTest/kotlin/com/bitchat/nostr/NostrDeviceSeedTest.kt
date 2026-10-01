package com.bitchat.nostr

import com.bitchat.transport.IdentityRefusedException
import com.bitchat.transport.IdentityStoreState
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val SEED_KEY = "nostr_device_seed"
private const val GEOHASH = "u4pruyd"

/**
 * The device seed is the root secret of every geohash identity: each one is derived from it.
 *
 * Whatever goes wrong around it, the answer may never be a weak seed (short, zero, or made up on
 * the spot) and may never be a replacement written over one that exists. Every failure below must
 * surface as an exception with nothing written, so no geohash identity is ever derived from it.
 * Where the bytes come from is `NostrSecretProvenanceTest`'s job; this is about custody.
 */
class NostrDeviceSeedTest {

    private fun client(store: FakeIdentityStore) = NostrClient(FakeNostrPreferences(), store)

    @Test
    fun `a first run mints one 32-byte seed and every later derivation reuses it`() {
        val store = FakeIdentityStore()

        val first = client(store).deriveIdentity(GEOHASH)
        // A fresh client has no identity cache, so this goes back to the store.
        val again = client(store).deriveIdentity(GEOHASH)

        assertEquals(1, store.mintCalls)
        assertEquals(32, Base64.decode(store.values.getValue(SEED_KEY)).size)
        assertEquals(first.privateKeyHex, again.privateKeyHex)
    }

    @Test
    fun `a refusal from the custodian yields no identity and writes nothing`() {
        // What the embedded custodian says when its ledger records a seed the store has lost:
        // the seed must be recovered, not replaced. Swallowing this and carrying on with some
        // other seed is exactly the silent weak seed this guards against.
        val refusal = IdentityRefusedException(
            reason = "this device has already created NOSTR_DEVICE_SEED (claim.nostr_seed=present " +
                "in the identity ledger) but '$SEED_KEY' is not in the store; the private key must " +
                "be recovered, not replaced",
            remedy = "restore the identity store from a backup",
        )
        val store = FakeIdentityStore(mapOf("nostr_private_key" to "live"), refusal = refusal)

        val thrown = assertFailsWith<IdentityRefusedException> { client(store).deriveIdentity(GEOHASH) }

        assertSame(refusal, thrown)
        assertEquals(0, store.mintCalls)
        assertEquals(mapOf("nostr_private_key" to "live"), store.values)
    }

    @Test
    fun `an unreadable store refuses to mint a seed`() {
        val store = FakeIdentityStore(
            mapOf("signing_private_key" to "signing"),
            state = IdentityStoreState.UNREADABLE,
        )

        val refusal = assertFailsWith<IdentityRefusedException> { client(store).deriveIdentity(GEOHASH) }

        assertTrue(refusal.reason.contains(SEED_KEY), refusal.reason)
        assertEquals(0, store.mintCalls)
        assertFalse(SEED_KEY in store.values)
    }

    @Test
    fun `a seed that fails to persist yields no identity and does not poison the next attempt`() {
        val failure = IllegalStateException("disk full")
        val store = FakeIdentityStore(saveFailure = failure)

        assertSame(failure, assertFailsWith<IllegalStateException> { client(store).deriveIdentity(GEOHASH) })
        assertFalse(SEED_KEY in store.values)

        store.saveFailure = null
        client(store).deriveIdentity(GEOHASH)

        assertEquals(2, store.mintCalls)
        assertEquals(32, Base64.decode(store.values.getValue(SEED_KEY)).size)
    }

    @Test
    fun `a stored seed of the wrong length is refused and left where it is`() {
        // A store truncated mid-record, or a seed written by a broken build. Deriving from it
        // would make every geohash key guessable; replacing it would abandon them all.
        for (size in listOf(0, 1, 16, 31, 33, 64)) {
            val stored = Base64.encode(ByteArray(size) { (it + 1).toByte() })
            val store = FakeIdentityStore(mapOf(SEED_KEY to stored))

            val refusal = assertFailsWith<IdentityRefusedException>("$size bytes") {
                client(store).deriveIdentity(GEOHASH)
            }

            assertTrue(refusal.reason.contains("it is $size bytes"), refusal.reason)
            assertEquals(stored, store.values[SEED_KEY], "$size bytes")
            assertEquals(0, store.mintCalls, "$size bytes")
        }
    }

    @Test
    fun `an all-zero stored seed is refused and left where it is`() {
        val stored = Base64.encode(ByteArray(32))
        val store = FakeIdentityStore(mapOf(SEED_KEY to stored))

        val refusal = assertFailsWith<IdentityRefusedException> { client(store).deriveIdentity(GEOHASH) }

        assertTrue(refusal.reason.contains("all zero"), refusal.reason)
        assertEquals(stored, store.values[SEED_KEY])
        assertEquals(0, store.mintCalls)
    }

    @Test
    fun `a stored seed that is not base64 is refused and left where it is`() {
        val store = FakeIdentityStore(mapOf(SEED_KEY to "not base64 at all!"))

        val refusal = assertFailsWith<IdentityRefusedException> { client(store).deriveIdentity(GEOHASH) }

        assertTrue(refusal.reason.contains("not valid base64"), refusal.reason)
        assertEquals("not base64 at all!", store.values[SEED_KEY])
        assertEquals(0, store.mintCalls)
    }
}
