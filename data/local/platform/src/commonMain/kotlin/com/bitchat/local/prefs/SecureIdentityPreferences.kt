package com.bitchat.local.prefs

interface SecureIdentityPreferences {
    fun loadStaticKey(): Pair<ByteArray, ByteArray>?
    fun saveStaticKey(privateKey: ByteArray, publicKey: ByteArray)
    fun loadSigningKey(): Pair<ByteArray, ByteArray>?
    fun saveSigningKey(privateKey: ByteArray, publicKey: ByteArray)

    fun isValidFingerprint(fingerprint: String): Boolean
    fun validatePublicKey(publicKey: ByteArray): Boolean
    fun validatePrivateKey(privateKey: ByteArray): Boolean
    fun getDebugInfo(): String
    fun clearIdentityData()
    fun hasIdentityData(): Boolean
    fun storeSecureValue(key: String, value: String)
    fun getSecureValue(key: String): String?
    fun removeSecureValue(key: String)
    fun hasSecureValue(key: String): Boolean
    fun clearSecureValues(vararg keys: String)
}
