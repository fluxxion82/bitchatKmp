package com.bitchat.local.prefs.impl

import com.bitchat.local.identity.DomainInspector
import com.bitchat.local.identity.IdentityComponent
import com.bitchat.local.identity.IdentityCustodian
import com.bitchat.local.identity.IdentityRecordStore
import com.bitchat.local.identity.LedgerStore
import com.bitchat.local.identity.NoDomainInspector
import com.bitchat.local.identity.NoLedgerStore
import com.bitchat.local.prefs.EncryptionSettingsFactory
import com.bitchat.local.prefs.HealthReportingSettings
import com.bitchat.local.prefs.PreferenceStoreState
import com.bitchat.local.prefs.SecureIdentityPreferences
import com.bitchat.local.util.toHexString
import com.bitchat.transport.IdentityRefusedException
import com.russhwolf.settings.contains
import kotlin.io.encoding.Base64.Default.decode
import kotlin.io.encoding.Base64.Default.encode

/**
 * @param domainInspector and [ledgerStore] default to the no-op pair, which is the correct
 *   configuration for Android, Apple and JVM desktop: their stores raise a read failure rather
 *   than handing back a half-loaded map, so there is no directory domain to scan. The embedded
 *   Linux build registers real ones in its `localModule`.
 */
class LocalSecureIdentityPreferences(
    encryptedPreferenceFactory: EncryptionSettingsFactory,
    domainInspector: DomainInspector = NoDomainInspector,
    ledgerStore: LedgerStore = NoLedgerStore,
) : SecureIdentityPreferences {
    val settings = encryptedPreferenceFactory.createEncrypted(PREFS_NAME)

    /**
     * The single gate every mint in this process passes through. It is constructed here, and
     * only here, so that there is exactly one of it and no Koin cycle between it and this store.
     */
    private val custodian = IdentityCustodian(
        store = object : IdentityRecordStore {
            override fun record(key: String): String? = settings.getStringOrNull(key)
            override fun state(): PreferenceStoreState = storeState()
        },
        inspector = domainInspector,
        ledgerStore = ledgerStore,
    )

    override fun loadOrMintSigningKey(
        mint: () -> Pair<ByteArray, ByteArray>,
    ): Pair<ByteArray, ByteArray> = custodian.loadOrMint(
        component = IdentityComponent.MESH_SIGNING,
        load = ::loadSigningKey,
        claimOf = { (_, publicKey) -> publicKey.toHexString() },
        persist = { (privateKey, publicKey) -> saveSigningKey(privateKey, publicKey) },
        mint = mint,
    )

    override fun loadOrMintSecureValue(
        key: String,
        publicFormOf: (String) -> String,
        mint: () -> String,
    ): String {
        val component = IdentityComponent.forStoreKey(key)
            ?: throw IdentityRefusedException(
                reason = "'$key' is not a known identity component, so the first-run invariant " +
                    "cannot be evaluated for it",
                remedy = "add it to IdentityComponent before creating it through this path",
            )

        return custodian.loadOrMint(
            component = component,
            load = { settings.getStringOrNull(key) },
            claimOf = publicFormOf,
            persist = { settings.putString(key, it) },
            mint = mint,
        )
    }

    override fun loadStaticKey(): Pair<ByteArray, ByteArray>? {
        return try {
            val privateKeyString = settings.getStringOrNull(KEY_STATIC_PRIVATE_KEY)
            val publicKeyString = settings.getStringOrNull(KEY_STATIC_PUBLIC_KEY)

            if (privateKeyString != null && publicKeyString != null) {
                val privateKey = decode(privateKeyString)
                val publicKey = decode(publicKeyString)

                if (privateKey.size == 32 && publicKey.size == 32) {
                    Pair(privateKey, publicKey)
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override fun saveStaticKey(privateKey: ByteArray, publicKey: ByteArray) {
        try {
            if (privateKey.size != 32 || publicKey.size != 32) {
                throw IllegalArgumentException("Invalid key sizes: private=${privateKey.size}, public=${publicKey.size}")
            }

            val privateKeyString = encode(privateKey)
            val publicKeyString = encode(publicKey)

            settings.putString(KEY_STATIC_PRIVATE_KEY, privateKeyString)
            settings.putString(KEY_STATIC_PUBLIC_KEY, publicKeyString)
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }

    override fun loadSigningKey(): Pair<ByteArray, ByteArray>? {
        return try {
            val privateKeyString = settings.getStringOrNull(KEY_SIGNING_PRIVATE_KEY)
            val publicKeyString = settings.getStringOrNull(KEY_SIGNING_PUBLIC_KEY)

            if (privateKeyString != null && publicKeyString != null) {
                val privateKey = decode(privateKeyString)
                val publicKey = decode(publicKeyString)

                if (privateKey.size == 32 && publicKey.size == 32) {
                    Pair(privateKey, publicKey)
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override fun saveSigningKey(privateKey: ByteArray, publicKey: ByteArray) {
        try {
            if (privateKey.size != 32 || publicKey.size != 32) {
                throw IllegalArgumentException("Invalid signing key sizes: private=${privateKey.size}, public=${publicKey.size}")
            }

            val privateKeyString = encode(privateKey)
            val publicKeyString = encode(publicKey)

            settings.putString(KEY_SIGNING_PRIVATE_KEY, privateKeyString)
            settings.putString(KEY_SIGNING_PUBLIC_KEY, publicKeyString)
        } catch (e: Exception) {
            throw e
        }
    }

    override fun isValidFingerprint(fingerprint: String): Boolean {
        return fingerprint.matches(Regex("^[a-fA-F0-9]{64}$"))
    }

    override fun validatePublicKey(publicKey: ByteArray): Boolean {
        if (publicKey.size != 32) return false

        if (publicKey.all { it == 0.toByte() }) return false

        val invalidPoints = setOf(
            ByteArray(32) { 0x00.toByte() },
            ByteArray(32) { 0xFF.toByte() },
        )

        return !invalidPoints.any { it.contentEquals(publicKey) }
    }

    override fun validatePrivateKey(privateKey: ByteArray): Boolean {
        if (privateKey.size != 32) return false

        if (privateKey.all { it == 0.toByte() }) return false

        val clampedKey = privateKey.copyOf()
        clampedKey[0] = (clampedKey[0].toInt() and 248).toByte()
        clampedKey[31] = (clampedKey[31].toInt() and 127).toByte()
        clampedKey[31] = (clampedKey[31].toInt() or 64).toByte()

        return !clampedKey.all { it == 0.toByte() }
    }

    override fun getDebugInfo(): String = buildString {
        appendLine("=== Identity State Manager Debug ===")

        val hasIdentity = settings.contains(KEY_STATIC_PRIVATE_KEY)
        appendLine("Has identity: $hasIdentity")

        if (hasIdentity) {
            try {
                val keyPair = loadStaticKey()
                if (keyPair != null) {
                    val fingerprint = keyPair.second.toHexString()
                    appendLine("Identity fingerprint: ${fingerprint.take(16)}...")
                    appendLine("Key validation: private=${validatePrivateKey(keyPair.first)}, public=${validatePublicKey(keyPair.second)}")
                }
            } catch (e: Exception) {
                appendLine("Key validation failed: ${e.message}")
            }
        }
    }

    override fun clearIdentityData() {
        settings.clear()
    }

    override fun hasIdentityData(): Boolean {
        return settings.contains(KEY_STATIC_PRIVATE_KEY) && settings.contains(KEY_STATIC_PUBLIC_KEY)
    }

    override fun storeSecureValue(key: String, value: String) {
        settings.putString(key, value)
    }

    override fun getSecureValue(key: String): String? {
        return settings.getStringOrNull(key)
    }

    override fun removeSecureValue(key: String) {
        settings.remove(key)
    }

    override fun hasSecureValue(key: String): Boolean {
        return settings.contains(key)
    }

    override fun storeState(): PreferenceStoreState = PreferenceStoreState.of(
        // Only the file-backed embedded store can hand back a half-loaded store; the Keychain
        // and EncryptedSharedPreferences backends throw instead, so they are always intact.
        damage = (settings as? HealthReportingSettings)?.storeDamage.orEmpty(),
        isEmpty = settings.keys.isEmpty(),
    )

    override fun clearSecureValues(vararg keys: String) {
        keys.forEach { key ->
            settings.remove(key)
        }
    }

    companion object {
        private const val PREFS_NAME = "bitchat_identity"
        private const val KEY_STATIC_PRIVATE_KEY = "static_private_key"
        private const val KEY_STATIC_PUBLIC_KEY = "static_public_key"
        private const val KEY_SIGNING_PRIVATE_KEY = "signing_private_key"
        private const val KEY_SIGNING_PUBLIC_KEY = "signing_public_key"
    }
}
