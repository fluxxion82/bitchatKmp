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

    /**
     * How the backing store presented itself when it was opened.
     *
     * [getSecureValue] returns null both for a key that was never written and for a key whose
     * store failed to load. Callers that would create the missing key must tell those apart.
     */
    fun storeState(): PreferenceStoreState
}
