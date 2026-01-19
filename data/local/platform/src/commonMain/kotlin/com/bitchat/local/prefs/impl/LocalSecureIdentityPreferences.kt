package com.bitchat.local.prefs.impl

import com.bitchat.local.prefs.EncryptionSettingsFactory
import com.bitchat.local.prefs.SecureIdentityPreferences
import com.bitchat.local.util.toHexString
import com.russhwolf.settings.contains
import kotlin.io.encoding.Base64.Default.decode
import kotlin.io.encoding.Base64.Default.encode

class LocalSecureIdentityPreferences(
    encryptedPreferenceFactory: EncryptionSettingsFactory,
) : SecureIdentityPreferences {
    val settings = encryptedPreferenceFactory.createEncrypted(PREFS_NAME)

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
