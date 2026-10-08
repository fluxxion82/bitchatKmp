package com.bitchat.repo.di

import com.bitchat.bluetooth.di.bluetoothModule
import com.bitchat.bluetooth.facade.CryptoSigningFacade
import com.bitchat.local.identity.DomainInspector
import com.bitchat.local.identity.LedgerStore
import com.bitchat.local.identity.NoDomainInspector
import com.bitchat.local.identity.NoLedgerStore
import com.bitchat.local.prefs.BlockListPreferences
import com.bitchat.local.prefs.EncryptionSettingsFactory
import com.bitchat.local.prefs.SecureIdentityPreferences
import com.bitchat.local.prefs.SecureStoreUnavailableException
import com.bitchat.local.prefs.UserPreferences
import com.bitchat.local.prefs.isSecureStoreUnavailable
import com.bitchat.local.prefs.impl.LocalBlockListPreferences
import com.bitchat.local.prefs.impl.LocalSecureIdentityPreferences
import com.bitchat.local.prefs.impl.LocalUserPreferences
import com.bitchat.transport.IdentityRefusedException
import com.russhwolf.settings.PropertiesSettings
import com.russhwolf.settings.Settings
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.util.Properties
import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A start on a locked phone: the secure store refuses, the start says so instead of failing, and
 * the next try after the unlock has the identity that was there all along.
 */
class SecureStoreStartTest {
    private val stores = LockableStores()
    private val application = koinApplication {
        modules(
            bluetoothModule,
            module {
                single<EncryptionSettingsFactory> { stores }
                single<DomainInspector> { NoDomainInspector }
                single<LedgerStore> { NoLedgerStore }
                single<UserPreferences> { LocalUserPreferences(encryptedPreferenceFactory = get()) }
                single<BlockListPreferences> { LocalBlockListPreferences(encryptedPreferenceFactory = get()) }
                single<SecureIdentityPreferences> {
                    LocalSecureIdentityPreferences(encryptedPreferenceFactory = get(), domainInspector = get(), ledgerStore = get())
                }
            },
        )
    }
    private val koin: Koin = application.koin

    // bluetoothModule is one object for every test here, and a module keeps what its singles
    // built: without this the identity one test loaded would be handed to the next.
    @AfterTest
    fun close() = application.close()

    @Test
    fun `a start with a store that will not open says locked and builds nothing`() {
        for (store in listOf("userPreferences", "block_list_prefs", "bitchat_identity")) {
            stores.refuseToOpen = setOf(store)

            assertIs<SecureStoreStart.Unavailable>(koin.openSecureStores(), store)
        }

        assertEquals(emptyList(), stores.writes)
    }

    @Test
    fun `a start whose identity cannot be read says locked and creates none`() {
        stores.saveSigningKey(seed = 1)
        stores.refuseReads = true

        assertIs<SecureStoreStart.Unavailable>(koin.openSecureStores())

        assertEquals(emptyList(), stores.writes)
    }

    @Test
    fun `a start whose identity is listed and not returned says locked and creates none`() {
        // No refusal anywhere: every read says "nothing here" while the listing names the key.
        stores.saveSigningKey(seed = 1)
        stores.hideValues = true

        assertIs<SecureStoreStart.Unavailable>(koin.openSecureStores())
        assertEquals(emptyList(), stores.writes)

        stores.hideValues = false
        assertEquals(SecureStoreStart.Ready, koin.openSecureStores())
        assertEquals(CryptoSigningFacade(SEED_1_HEX).getIdentityFingerprint(), koin.get<CryptoSigningFacade>().getIdentityFingerprint())
    }

    @Test
    fun `a start whose identity was hidden for one read says locked and then has the identity that was there`() {
        // The first read of the signing key says "nothing here"; the look that follows it, the
        // phone unlocked meanwhile, returns the key. Nothing refused, nothing listed wrongly.
        stores.saveSigningKey(seed = 1)
        stores.hideNextReads = 1

        assertIs<SecureStoreStart.Unavailable>(koin.openSecureStores())
        assertEquals(emptyList(), stores.writes)

        assertEquals(SecureStoreStart.Ready, koin.openSecureStores())
        assertEquals(CryptoSigningFacade(SEED_1_HEX).getIdentityFingerprint(), koin.get<CryptoSigningFacade>().getIdentityFingerprint())
        assertEquals(Base64.encode(ByteArray(32) { 1 }), stores.signingPrivateKey())
    }

    @Test
    fun `the try after the unlock has the identity that was there`() {
        stores.saveSigningKey(seed = 1)
        stores.refuseToOpen = setOf("bitchat_identity")
        assertIs<SecureStoreStart.Unavailable>(koin.openSecureStores())
        stores.refuseToOpen = emptySet()
        stores.refuseReads = true
        assertIs<SecureStoreStart.Unavailable>(koin.openSecureStores())
        stores.refuseReads = false

        assertEquals(SecureStoreStart.Ready, koin.openSecureStores())

        assertEquals(CryptoSigningFacade(SEED_1_HEX).getIdentityFingerprint(), koin.get<CryptoSigningFacade>().getIdentityFingerprint())
        assertEquals(Base64.encode(ByteArray(32) { 1 }), stores.signingPrivateKey(), "the stored identity was written over")
    }

    @Test
    fun `a first start creates its identity and is ready`() {
        assertEquals(SecureStoreStart.Ready, koin.openSecureStores())

        val created = stores.signingPrivateKey()
        assertTrue(created != null && "signing_private_key" in stores.writes, stores.writes.toString())
        assertEquals(SecureStoreStart.Ready, koin.openSecureStores())
        assertEquals(created, stores.signingPrivateKey())
    }

    @Test
    fun `a start whose stored public key cannot be corrected says so and then has the identity that was there`() {
        // The key pair is read; writing the corrected public key back is refused.
        stores.saveSigningKey(seed = 1)
        stores.refuseWrites = true

        val start = koin.openSecureStores()
        assertIs<SecureStoreStart.Unavailable>(start)
        assertEquals("the store is not answering", start.reason)

        stores.refuseWrites = false
        assertEquals(SecureStoreStart.Ready, koin.openSecureStores())
        assertEquals(CryptoSigningFacade(SEED_1_HEX).getIdentityFingerprint(), koin.get<CryptoSigningFacade>().getIdentityFingerprint())
        assertEquals(Base64.encode(ByteArray(32) { 1 }), stores.signingPrivateKey())
    }

    @Test
    fun `a start with a damaged identity record fails as before`() {
        // Not the store's failure: the record was returned and is no key. Trying again cures nothing.
        stores.saveSigningKey(seed = 1)
        stores.damageSigningKey()

        val failure = assertFailsWith<Exception> { koin.openSecureStores() }

        assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it is IdentityRefusedException }, failure.toString())
        assertFalse(failure.isSecureStoreUnavailable())
        assertEquals(emptyList(), stores.writes)
    }

    private companion object {
        val SEED_1_HEX = "01".repeat(32)
    }
}

/** Secure stores that refuse the way a locked Keychain does: at opening, or at every read. */
private class LockableStores : EncryptionSettingsFactory {
    private val saved = mutableMapOf<String, PropertiesSettings>()
    var refuseToOpen = emptySet<String>()
    var refuseReads = false
    /** Every read answers "nothing under this key" while the listing still names what is saved. */
    var hideValues = false
    /** This many reads answer "nothing under this key", then the stores show their values again. */
    var hideNextReads = 0
    var refuseWrites = false
    val writes = mutableListOf<String>()

    private fun settings(name: String) = saved.getOrPut(name) { PropertiesSettings(Properties()) }

    fun saveSigningKey(seed: Int) {
        val privateKey = ByteArray(32) { seed.toByte() }
        settings("bitchat_identity").putString("signing_private_key", Base64.encode(privateKey))
        // Left for the start to correct: the public key is derived from the private one.
        settings("bitchat_identity").putString("signing_public_key", Base64.encode(ByteArray(32) { 9 }))
        writes.clear()
    }

    fun damageSigningKey() {
        settings("bitchat_identity").putString("signing_private_key", "not a key")
        writes.clear()
    }

    fun signingPrivateKey(): String? = settings("bitchat_identity").getStringOrNull("signing_private_key")

    override fun createEncrypted(name: String): Settings {
        if (name in refuseToOpen) throw SecureStoreUnavailableException("'$name' is locked")
        return Store(settings(name))
    }

    private inner class Store(private val delegate: Settings) : Settings by delegate {
        override val keys: Set<String>
            get() {
                refuse()
                return delegate.keys
            }

        override fun getStringOrNull(key: String): String? {
            refuse()
            if (hideNextReads > 0) {
                hideNextReads--
                return null
            }
            return if (hideValues) null else delegate.getStringOrNull(key)
        }

        override fun putString(key: String, value: String) {
            if (refuseWrites) throw SecureStoreUnavailableException("the store is not answering")
            writes += key
            delegate.putString(key, value)
        }

        private fun refuse() {
            if (refuseReads) throw SecureStoreUnavailableException("the store is locked")
        }
    }
}
