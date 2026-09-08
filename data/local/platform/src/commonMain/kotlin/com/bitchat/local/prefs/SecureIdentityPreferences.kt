package com.bitchat.local.prefs

interface SecureIdentityPreferences {
    fun loadStaticKey(): Pair<ByteArray, ByteArray>?
    fun saveStaticKey(privateKey: ByteArray, publicKey: ByteArray)
    fun loadSigningKey(): Pair<ByteArray, ByteArray>?
    fun saveSigningKey(privateKey: ByteArray, publicKey: ByteArray)

    /**
     * Returns the stored mesh signing keypair, creating one only when this device can prove it
     * has never created one before.
     *
     * This is how the Bluetooth module reaches the identity guard. It cannot go through
     * `TransportIdentityProvider` because `:data:remote:transport:bluetooth` does not depend on
     * `:data:remote:transport`; both entry points are thin delegations to the same custodian.
     *
     * The signing key is the *first* identity write on a new device - it happens while Koin
     * builds the graph, before Nostr is ever asked for its key - and until this existed it was
     * the one mint site with no guard at all.
     *
     * @param mint creates a (private, public) pair. Called only when the guard allows it.
     * @throws com.bitchat.transport.IdentityRefusedException when creating one could overwrite
     *   key material that is still on this device.
     */
    fun loadOrMintSigningKey(mint: () -> Pair<ByteArray, ByteArray>): Pair<ByteArray, ByteArray>

    /**
     * Returns the stored value for [key], creating one only when the guard allows it.
     *
     * @param publicFormOf maps a stored value to the public value recorded against it in the
     *   identity ledger.
     * @throws com.bitchat.transport.IdentityRefusedException when creating it could abandon a
     *   recoverable identity.
     */
    fun loadOrMintSecureValue(
        key: String,
        publicFormOf: (String) -> String,
        mint: () -> String,
    ): String

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
