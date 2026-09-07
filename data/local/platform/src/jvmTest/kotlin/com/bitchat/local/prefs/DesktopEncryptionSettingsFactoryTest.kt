package com.bitchat.local.prefs

import com.microsoft.credentialstorage.SecretStore
import com.microsoft.credentialstorage.model.StoredCredential
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `StorageProvider.getCredentialStorage(persist = true, REQUIRED)` returns **null** whenever no
 * backend qualifies. On Linux that needs libsecret plus an unlocked secret-service collection on a
 * live session bus, so a headless box, an SSH session or a fresh install all get null - and the
 * old code dereferenced it straight away, producing a bare NullPointerException nested inside a
 * Koin instance-creation stack trace.
 */
class DesktopEncryptionSettingsFactoryTest {

    @Test
    fun `null credential store fails with an actionable message, not a NullPointerException`() {
        val factory = DesktopEncryptionSettingsFactory(credentialStorageProvider = { null })

        val failure = assertFailsWith<SecureStorageUnavailableException> {
            factory.createEncrypted("user")
        }

        val message = failure.message
        assertTrue(message != null, "expected a message")
        assertTrue(
            message.contains("bitchat secure storage unavailable on "),
            "expected an OS-qualified headline, got: $message"
        )
        assertTrue(
            message.contains(System.getProperty("os.name")),
            "expected the OS name in the message, got: $message"
        )
        assertTrue(
            message.contains("StorageProvider.getCredentialStorage returned null"),
            "expected the null store named, got: $message"
        )
        assertTrue(
            message.contains("libsecret") && message.contains("gnome-keyring"),
            "expected the Linux libsecret fix, got: $message"
        )
        assertTrue(
            message.contains("UNLOCKED"),
            "expected the unlocked-keyring requirement, got: $message"
        )
        assertTrue(
            message.contains("will not fall back to unencrypted storage"),
            "expected the no-plaintext-fallback promise, got: $message"
        )
    }

    @Test
    fun `a throwing credential store probe is wrapped, including Errors`() {
        val boom = UnsatisfiedLinkError("libsecret-1.so.0 not found")
        val factory = DesktopEncryptionSettingsFactory(credentialStorageProvider = { throw boom })

        val failure = assertFailsWith<SecureStorageUnavailableException> {
            factory.createEncrypted("user")
        }

        assertSame(boom, failure.cause)
        assertTrue(
            failure.message!!.contains("libsecret-1.so.0 not found"),
            "expected the underlying reason, got: ${failure.message}"
        )
    }

    @Test
    fun `a checked exception from the probe is wrapped too`() {
        val boom = IllegalStateException("no session bus")
        val factory = DesktopEncryptionSettingsFactory(credentialStorageProvider = { throw boom })

        val failure = assertFailsWith<SecureStorageUnavailableException> {
            factory.createEncrypted("user")
        }

        assertSame(boom, failure.cause)
    }

    @Test
    fun `a dying JVM is not reported as a missing keyring`() {
        val factory = DesktopEncryptionSettingsFactory(
            credentialStorageProvider = { throw OutOfMemoryError("heap") }
        )

        // Catching bare Throwable swallowed VirtualMachineError and blamed the keyring for it.
        assertFailsWith<OutOfMemoryError> { factory.createEncrypted("user") }
    }

    @Test
    fun `the store is probed once so startup shows one failure, not one per preferences file`() {
        var probes = 0
        val factory = DesktopEncryptionSettingsFactory(
            credentialStorageProvider = {
                probes++
                null
            }
        )

        // LocalUserPreferences, LocalSecureIdentityPreferences and LocalBlockListPreferences each
        // call createEncrypted from a property initialiser during the eager AppInitializer sweep.
        val failures = List(3) {
            assertFailsWith<SecureStorageUnavailableException> { factory.createEncrypted("prefs-$it") }
        }

        assertEquals(1, probes, "the credential store must only be probed once")
        assertSame(failures[0], failures[1])
        assertSame(failures[0], failures[2])
    }

    @Test
    fun `a working credential store still mints and then reuses one master key`() {
        val store = InMemoryCredentialStore()
        val factory = DesktopEncryptionSettingsFactory(credentialStorageProvider = { store })

        // Deliberately does not write through the returned Settings: EncryptedPreferences delegates
        // to the real java.util.prefs user root, and a test has no business editing it.
        factory.createEncrypted("user")
        val minted = store.entries[MASTER_KEY_NAME]
        assertTrue(minted != null, "a master key should have been created")

        factory.createEncrypted("blocklist")
        assertSame(minted, store.entries[MASTER_KEY_NAME], "the master key must be reused, not rotated")
    }

    private class InMemoryCredentialStore : SecretStore<StoredCredential> {
        val entries = mutableMapOf<String, StoredCredential>()

        override fun get(key: String): StoredCredential? = entries[key]

        override fun delete(key: String): Boolean = entries.remove(key) != null

        override fun add(key: String, secret: StoredCredential): Boolean {
            entries[key] = secret
            return true
        }

        override fun isSecure(): Boolean = true
    }
}
