package com.bitchat.local.prefs

import com.bitchat.domain.user.model.AppUser
import com.bitchat.domain.user.model.BlockType
import com.bitchat.domain.user.model.BlockedUser
import com.bitchat.domain.user.model.FavoriteRelationship
import com.bitchat.local.identity.NoDomainInspector
import com.bitchat.local.identity.NoLedgerStore
import com.bitchat.local.prefs.impl.LocalBlockListPreferences
import com.bitchat.local.prefs.impl.LocalSecureIdentityPreferences
import com.bitchat.local.prefs.impl.LocalUserPreferences
import com.bitchat.local.transport.SecureTransportIdentityProvider
import com.bitchat.transport.IdentityRefusedException
import com.russhwolf.settings.PropertiesSettings
import com.russhwolf.settings.Settings
import java.util.Properties
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A secure store that does not answer, as the Keychain does on a locked phone: nothing may be
 * created or written over because a read failed.
 */
class UnreadableSecureStoreTest {
    private val stored = PropertiesSettings(Properties())
    private val store = FailingStore(stored)
    private val factory = object : EncryptionSettingsFactory {
        override fun createEncrypted(name: String): Settings = store
    }

    // --- nickname ---

    @Test
    fun `a nickname that cannot be read is not replaced by a random one`() {
        stored.putString("userName", "alice")
        val preferences = LocalUserPreferences(factory)
        store.failReads = true

        assertFailsWith<SecureStoreUnavailableException> { preferences.getAppUser() }

        assertEquals(emptyList(), store.writes)
        store.failReads = false
        assertEquals(AppUser.ActiveAnonymous("alice"), preferences.getAppUser())
    }

    @Test
    fun `a nickname the store says it does not have is created once and kept`() {
        val preferences = LocalUserPreferences(factory)

        val first = preferences.getAppUser()

        assertIs<AppUser.ActiveAnonymous>(first)
        assertEquals(listOf("userName"), store.writes)
        assertEquals(first, preferences.getAppUser())
        assertEquals(listOf("userName"), store.writes)
    }

    @Test
    fun `a nickname the store wrongly reports absent is not replaced`() {
        stored.putString("userName", "alice")
        val preferences = LocalUserPreferences(factory)
        store.hideValues = true

        assertFailsWith<SecureStoreUnavailableException> { preferences.getAppUser() }

        assertEquals(emptyList(), store.writes)
        assertEquals("alice", stored.getStringOrNull("userName"))
    }

    @Test
    fun `two first calls end with one nickname`() {
        // Another caller creates the name between this one's read and its add.
        val preferences = LocalUserPreferences(factory)
        store.beforeNextAdd = { stored.putString("userName", "anon1234") }

        assertEquals(AppUser.ActiveAnonymous("anon1234"), preferences.getAppUser())

        assertEquals("anon1234", stored.getStringOrNull("userName"))
    }

    // --- favourites ---

    @Test
    fun `a favourite is not saved over favourites that could not be read`() {
        val preferences = LocalUserPreferences(factory)
        preferences.saveFavorite(favorite("aa"))
        preferences.saveFavorite(favorite("bb"))
        store.writes.clear()
        store.failReads = true

        assertFailsWith<SecureStoreUnavailableException> { preferences.saveFavorite(favorite("cc")) }
        assertFailsWith<SecureStoreUnavailableException> { preferences.deleteFavorite("aa") }
        assertFailsWith<SecureStoreUnavailableException> {
            preferences.updateFavorites { FavoritesUpdate(Unit, save = listOf(favorite("dd"))) }
        }

        assertEquals(emptyList(), store.writes)
        store.failReads = false
        assertEquals(setOf("aa", "bb"), preferences.getAllFavorites().keys)
    }

    @Test
    fun `the decision about the favourites is not asked for when they could not be read`() {
        val preferences = LocalUserPreferences(factory)
        store.failReads = true
        var asked = false

        assertFailsWith<SecureStoreUnavailableException> {
            preferences.updateFavorites { asked = true; FavoritesUpdate(Unit) }
        }

        assertFalse(asked)
    }

    @Test
    fun `a last-read time is not saved over times that could not be read`() {
        val preferences = LocalUserPreferences(factory)
        preferences.setLastReadTimestamp("aa", 1)
        preferences.setLastReadTimestamp("bb", 2)
        store.writes.clear()
        store.failReads = true

        preferences.setLastReadTimestamp("cc", 3)

        assertEquals(emptyList(), store.writes)
        store.failReads = false
        assertEquals(mapOf("aa" to 1L, "bb" to 2L), preferences.getAllLastReadTimestamps())
    }

    // --- block list ---

    @Test
    fun `a block is not saved over a block list that could not be read`() {
        val preferences = LocalBlockListPreferences(factory)
        preferences.addMeshBlockedUser(blocked("aa", BlockType.MESH))
        preferences.addGeohashBlockedUser(blocked("bb", BlockType.GEOHASH))
        store.writes.clear()
        store.failReads = true

        preferences.addMeshBlockedUser(blocked("cc", BlockType.MESH))
        preferences.removeMeshBlockedUser("aa")
        preferences.addGeohashBlockedUser(blocked("dd", BlockType.GEOHASH))
        preferences.removeGeohashBlockedUser("bb")

        assertEquals(emptyList(), store.writes)
        store.failReads = false
        assertEquals(setOf("aa"), preferences.getMeshBlockedUsers().keys)
        assertEquals(setOf("bb"), preferences.getGeohashBlockedUsers().keys)
    }

    @Test
    fun `lists the store wrongly reports absent are not replaced`() {
        val users = LocalUserPreferences(factory)
        val blocks = LocalBlockListPreferences(factory)
        users.saveFavorite(favorite("aa"))
        users.setLastReadTimestamp("aa", 1)
        blocks.addMeshBlockedUser(blocked("aa", BlockType.MESH))
        blocks.addGeohashBlockedUser(blocked("bb", BlockType.GEOHASH))
        val before = stored.keys.associateWith { stored.getStringOrNull(it) }
        store.writes.clear()
        store.hideValues = true

        users.saveFavorite(favorite("cc"))
        users.deleteFavorite("aa")
        users.setLastReadTimestamp("cc", 3)
        blocks.addMeshBlockedUser(blocked("cc", BlockType.MESH))
        blocks.removeMeshBlockedUser("aa")
        blocks.addGeohashBlockedUser(blocked("dd", BlockType.GEOHASH))
        blocks.removeGeohashBlockedUser("bb")

        assertEquals(emptyList(), store.writes)
        assertEquals(before, stored.keys.associateWith { stored.getStringOrNull(it) })
    }

    @Test
    fun `a write back that finds an item where its read found none says so`() {
        // The writers above swallow it; one that does not must learn that nothing was written.
        stored.putString("favorite_relationships", "saved")

        assertFailsWith<SecureStoreUnavailableException> {
            store.writeUpdate("favorite_relationships", SavedText(null, absent = true), "built from nothing")
        }
        store.writeUpdate("favorite_relationships", SavedText("saved", absent = false), "built from what was read")

        assertEquals(listOf("favorite_relationships"), store.writes)
        assertEquals("built from what was read", stored.getStringOrNull("favorite_relationships"))
    }

    @Test
    fun `a list that was read is written back over itself`() {
        val users = LocalUserPreferences(factory)
        val blocks = LocalBlockListPreferences(factory)
        users.saveFavorite(favorite("aa"))
        users.setLastReadTimestamp("aa", 1)
        blocks.addMeshBlockedUser(blocked("aa", BlockType.MESH))

        users.saveFavorite(favorite("bb"))
        users.setLastReadTimestamp("bb", 2)
        blocks.addMeshBlockedUser(blocked("bb", BlockType.MESH))

        assertEquals(setOf("aa", "bb"), users.getAllFavorites().keys)
        assertEquals(mapOf("aa" to 1L, "bb" to 2L), users.getAllLastReadTimestamps())
        assertEquals(setOf("aa", "bb"), blocks.getMeshBlockedUsers().keys)
    }

    // --- identity ---

    @Test
    fun `no signing key is created when the stored one could not be read`() {
        stored.putString("signing_private_key", Base64.encode(ByteArray(32) { 1 }))
        stored.putString("signing_public_key", Base64.encode(ByteArray(32) { 2 }))
        val identity = identityStore()
        store.failReads = true

        val refusal = assertFailsWith<IdentityRefusedException> { identity.loadOrMintSigningKey(::neverMint) }

        assertTrue(refusal.reason.contains("cannot be read"), refusal.reason)
        assertEquals(emptyList(), store.writes)
    }

    @Test
    fun `no signing key is created after a read that failed, whatever the store answers next`() {
        // The first look fails; every later one answers "nothing here", as a store would that
        // loses sight of its items while locked. A failed read ends it: nothing is created.
        val identity = identityStore()
        store.failNextReads = 1

        assertFailsWith<IdentityRefusedException> { identity.loadOrMintSigningKey(::neverMint) }

        assertEquals(emptyList(), store.writes)
    }

    @Test
    fun `no signing key is created when the store lists one it does not return`() {
        stored.putString("signing_private_key", Base64.encode(ByteArray(32) { 1 }))
        stored.putString("signing_public_key", Base64.encode(ByteArray(32) { 2 }))
        val identity = identityStore()
        store.hideValues = true

        val refusal = assertFailsWith<IdentityRefusedException> { identity.loadOrMintSigningKey(::neverMint) }

        assertTrue(refusal.reason.contains("listed"), refusal.reason)
        // The store's failure, not the record's: a start tries again.
        assertTrue(refusal.isSecureStoreUnavailable())
        assertEquals(emptyList(), store.writes)
    }

    @Test
    fun `no Nostr key or device seed is created when the store lists one it does not return`() {
        stored.putString("nostr_private_key", "aa")
        stored.putString("nostr_device_seed", "bb")
        val provider = SecureTransportIdentityProvider(identityStore())
        store.hideValues = true

        for (key in listOf("nostr_private_key", "nostr_device_seed")) {
            assertFailsWith<IdentityRefusedException> {
                provider.loadOrMint(key, publicFormOf = { it }, mint = { error("minted $key") })
            }
        }

        assertEquals(emptyList(), store.writes)
    }

    @Test
    fun `an identity the store hides from both the read and the listing is not written over`() {
        stored.putString("signing_private_key", Base64.encode(ByteArray(32) { 1 }))
        stored.putString("signing_public_key", Base64.encode(ByteArray(32) { 2 }))
        stored.putString("nostr_private_key", "aa")
        stored.putString("nostr_device_seed", "bb")
        val before = stored.keys.associateWith { stored.getStringOrNull(it) }
        val identity = identityStore()
        val provider = SecureTransportIdentityProvider(identity)
        store.hideValues = true
        store.hideListing = true

        // Nothing here can tell this from a first run, so a key is made. It reaches nobody, and
        // the refusal is one a start tries again after.
        val refusals = mutableListOf<IdentityRefusedException>()
        refusals += assertFailsWith<IdentityRefusedException> {
            identity.loadOrMintSigningKey { ByteArray(32) { 7 } to ByteArray(32) { 8 } }
        }
        for (key in listOf("nostr_private_key", "nostr_device_seed")) {
            refusals += assertFailsWith<IdentityRefusedException> { provider.loadOrMint(key, publicFormOf = { it }, mint = { "new" }) }
        }
        assertEquals(listOf(true, true, true), refusals.map { it.isSecureStoreUnavailable() })

        assertEquals(emptyList(), store.writes)
        assertEquals(before, stored.keys.associateWith { stored.getStringOrNull(it) })
    }

    @Test
    fun `an identity the store hid and then shows is not replaced, and the start is told to try again`() {
        // The first read says "nothing here"; the look that follows finds the record. Nothing
        // was refused anywhere, and the phone may have been unlocked in between.
        val privateKey = ByteArray(32) { 1 }
        stored.putString("signing_private_key", Base64.encode(privateKey))
        stored.putString("signing_public_key", Base64.encode(ByteArray(32) { 2 }))
        stored.putString("nostr_private_key", "aa")
        val identity = identityStore()
        val provider = SecureTransportIdentityProvider(identity)

        store.hideNextReads = 1
        val signing = assertFailsWith<IdentityRefusedException> { identity.loadOrMintSigningKey(::neverMint) }
        store.hideNextReads = 1
        val nostr = assertFailsWith<IdentityRefusedException> {
            provider.loadOrMint("nostr_private_key", publicFormOf = { it }, mint = { error("minted") })
        }

        assertTrue(signing.isSecureStoreUnavailable() && nostr.isSecureStoreUnavailable())
        assertEquals(emptyList(), store.writes)
        assertTrue(identity.loadOrMintSigningKey(::neverMint).first.contentEquals(privateKey))
        assertEquals("aa", provider.loadOrMint("nostr_private_key", publicFormOf = { it }, mint = { error("minted") }))
    }

    @Test
    fun `a signing key shown without its public half is not replaced, and the start is told to try again`() {
        stored.putString("signing_private_key", Base64.encode(ByteArray(32) { 1 }))
        stored.putString("signing_public_key", Base64.encode(ByteArray(32) { 2 }))
        val identity = identityStore()
        store.hideKeys = setOf("signing_public_key")

        val refusal = assertFailsWith<IdentityRefusedException> { identity.loadOrMintSigningKey(::neverMint) }

        assertTrue(refusal.isSecureStoreUnavailable())
        assertEquals(emptyList(), store.writes)
    }

    @Test
    fun `a signing key that was returned and is no key is refused for good and left where it is`() {
        val identity = identityStore()
        val whole = Base64.encode(ByteArray(32) { 1 })
        val short = Base64.encode(ByteArray(16) { 1 })
        // Either half may be the damaged one.
        for ((privateHalf, publicHalf) in listOf("not base64 !" to whole, short to whole, whole to "not base64 !", whole to short)) {
            val damaged = "$privateHalf / $publicHalf"
            stored.putString("signing_private_key", privateHalf)
            stored.putString("signing_public_key", publicHalf)
            store.writes.clear()

            val refusal = assertFailsWith<IdentityRefusedException>(damaged) { identity.loadOrMintSigningKey(::neverMint) }

            // Trying again does not cure it: a start fails with it instead of waiting.
            assertFalse(refusal.isSecureStoreUnavailable(), damaged)
            assertTrue(refusal.reason.contains("not a pair of 32-byte keys"), refusal.reason)
            assertEquals(emptyList(), store.writes)
            assertEquals(privateHalf, stored.getStringOrNull("signing_private_key"))
            assertEquals(publicHalf, stored.getStringOrNull("signing_public_key"))
        }
    }

    @Test
    fun `a first run that ends between the two halves of its signing key creates one on the next`() {
        // The half that is saved first goes in; the save of the second is refused.
        val identity = identityStore()
        store.failWritesOf = setOf("signing_private_key")
        assertFailsWith<IdentityRefusedException> {
            identity.loadOrMintSigningKey { ByteArray(32) { 1 } to ByteArray(32) { 2 } }
        }
        assertEquals(listOf("signing_public_key"), store.writes)

        store.failWritesOf = emptySet()
        val second = ByteArray(32) { 3 } to ByteArray(32) { 4 }
        val (privateKey, publicKey) = identity.loadOrMintSigningKey { second }

        assertTrue(privateKey.contentEquals(second.first) && publicKey.contentEquals(second.second))
        assertEquals(Base64.encode(second.first), stored.getStringOrNull("signing_private_key"))
        // The public half of the first try was not written over; whoever loads the pair derives
        // the right one from the private key (the Bluetooth module does, and saves it).
        assertEquals(listOf("signing_public_key", "signing_private_key"), store.writes)
        assertTrue(identity.loadOrMintSigningKey(::neverMint).first.contentEquals(second.first))
    }

    @Test
    fun `a refusal because the store did not answer can be told from any other however it is wrapped`() {
        val identity = identityStore()
        store.failReads = true

        val refusal = assertFailsWith<IdentityRefusedException> { identity.loadOrMintSigningKey(::neverMint) }

        assertTrue(refusal.isSecureStoreUnavailable())
        assertTrue(RuntimeException("could not build the mesh service", refusal).isSecureStoreUnavailable())
        assertFalse(IdentityRefusedException("a damaged record", "restore it").isSecureStoreUnavailable())
        assertFalse(IllegalStateException("anything else").isSecureStoreUnavailable())
    }

    @Test
    fun `no signing key is created when the store cannot say what it holds`() {
        val identity = identityStore()
        store.failListing = true

        assertFailsWith<IdentityRefusedException> { identity.loadOrMintSigningKey(::neverMint) }

        assertEquals(emptyList(), store.writes)
    }

    @Test
    fun `a signing key that could not be saved is refused, not handed out`() {
        val identity = identityStore()
        store.failWrites = true

        assertFailsWith<IdentityRefusedException> {
            identity.loadOrMintSigningKey { ByteArray(32) { 1 } to ByteArray(32) { 2 } }
        }
    }

    @Test
    fun `no Nostr key or device seed is created when the store could not be read`() {
        val provider = SecureTransportIdentityProvider(identityStore())
        store.failReads = true

        for (key in listOf("nostr_private_key", "nostr_device_seed")) {
            assertFailsWith<IdentityRefusedException> {
                provider.loadOrMint(key, publicFormOf = { it }, mint = { error("minted $key") })
            }
        }

        assertEquals(emptyList(), store.writes)
    }

    @Test
    fun `a static key that could not be read is not reported as none`() {
        val privateKey = ByteArray(32) { 1 }
        val publicKey = ByteArray(32) { 2 }
        stored.putString("static_private_key", Base64.encode(privateKey))
        stored.putString("static_public_key", Base64.encode(publicKey))
        val identity = identityStore()

        // Either of its two reads may be the one that is refused.
        for (refusedRead in 1..2) {
            store.failOnRead = refusedRead
            assertFailsWith<SecureStoreUnavailableException>("read $refusedRead") { identity.loadStaticKey() }
        }

        val loaded = identity.loadStaticKey()
        assertTrue(loaded != null && loaded.first.contentEquals(privateKey) && loaded.second.contentEquals(publicKey))
    }

    @Test
    fun `a signing key that could not be read is not reported as none`() {
        val privateKey = ByteArray(32) { 1 }
        val publicKey = ByteArray(32) { 2 }
        stored.putString("signing_private_key", Base64.encode(privateKey))
        stored.putString("signing_public_key", Base64.encode(publicKey))
        val identity = identityStore()

        for (refusedRead in 1..2) {
            store.failOnRead = refusedRead
            assertFailsWith<SecureStoreUnavailableException>("read $refusedRead") { identity.loadSigningKey() }
        }

        val loaded = identity.loadSigningKey()
        assertTrue(loaded != null && loaded.first.contentEquals(privateKey) && loaded.second.contentEquals(publicKey))
    }

    @Test
    fun `a first run still creates its signing key`() {
        val identity = identityStore()
        val minted = ByteArray(32) { 1 } to ByteArray(32) { 2 }

        val (privateKey, publicKey) = identity.loadOrMintSigningKey { minted }

        assertTrue(privateKey.contentEquals(minted.first) && publicKey.contentEquals(minted.second))
        assertEquals(listOf("signing_public_key", "signing_private_key"), store.writes)
    }

    private fun identityStore() = LocalSecureIdentityPreferences(factory, NoDomainInspector, NoLedgerStore)

    private fun neverMint(): Pair<ByteArray, ByteArray> = error("a signing key was created")

    private fun favorite(key: String) = FavoriteRelationship(
        peerNoisePublicKeyHex = key,
        peerNostrPublicKey = null,
        peerNickname = key,
        isFavorite = true,
        theyFavoritedUs = false,
        favoritedAt = 0,
        lastUpdated = 0,
    )

    private fun blocked(id: String, type: BlockType) =
        BlockedUser(identifier = id, nickname = null, blockedAt = 0, blockType = type)
}

/**
 * [delegate], which can be told to stop answering reads, listings or writes, or to answer them
 * wrongly; every write that changed something is noted. Like the Keychain, it can add without
 * replacing.
 */
private class FailingStore(private val delegate: Settings) : Settings by delegate, AddOnlySettings {
    var failReads = false
    /** This many reads fail, then the store answers again. */
    var failNextReads = 0
    /** The read with this number, counted from the next one as 1, fails; the others answer. */
    var failOnRead = 0
    var failListing = false
    var failWrites = false
    /** Writes of these keys fail; the others go in. */
    var failWritesOf = emptySet<String>()
    /** Every read answers "nothing under this key" while the listing still names what is stored. */
    var hideValues = false
    /** This many reads answer "nothing under this key", then the store shows its values again. */
    var hideNextReads = 0
    /** Reads of these keys answer "nothing under this key". */
    var hideKeys = emptySet<String>()
    /** The listing names nothing, whatever is stored. */
    var hideListing = false
    /** Runs once, right before the next add looks at what is stored. */
    var beforeNextAdd: (() -> Unit)? = null
    val writes = mutableListOf<String>()

    private fun read() {
        if (failOnRead > 0 && --failOnRead == 0) throw SecureStoreUnavailableException("read failed")
        if (failNextReads > 0) {
            failNextReads--
            throw SecureStoreUnavailableException("read failed")
        }
        if (failReads) throw SecureStoreUnavailableException("read failed")
    }

    private fun write(key: String) {
        if (failWrites || key in failWritesOf) throw SecureStoreUnavailableException("write failed")
        writes += key
    }

    override val keys: Set<String>
        get() {
            if (failListing || failReads) throw SecureStoreUnavailableException("listing failed")
            return if (hideListing) emptySet() else delegate.keys
        }

    override fun putStringIfAbsent(key: String, value: String): Boolean {
        beforeNextAdd?.also { beforeNextAdd = null }?.invoke()
        if (delegate.hasKey(key)) return false
        write(key)
        delegate.putString(key, value)
        return true
    }

    override fun hasKey(key: String): Boolean {
        read()
        return delegate.hasKey(key)
    }

    override fun getStringOrNull(key: String): String? {
        read()
        if (hideNextReads > 0) {
            hideNextReads--
            return null
        }
        return if (hideValues || key in hideKeys) null else delegate.getStringOrNull(key)
    }

    override fun putString(key: String, value: String) {
        write(key)
        delegate.putString(key, value)
    }

    override fun remove(key: String) {
        write(key)
        delegate.remove(key)
    }
}
