package com.bitchat.local.transport

import com.bitchat.local.prefs.PreferenceStoreState
import com.bitchat.local.prefs.SecureIdentityPreferences
import com.bitchat.transport.IdentityStoreState
import com.bitchat.transport.TransportIdentityProvider

class SecureTransportIdentityProvider(
    private val securePrefs: SecureIdentityPreferences
) : TransportIdentityProvider {

    override fun loadKey(key: String): String? {
        return securePrefs.getSecureValue(key)
    }

    override fun saveKey(key: String, value: String) {
        securePrefs.storeSecureValue(key, value)
    }

    override fun hasKey(key: String): Boolean {
        return securePrefs.hasSecureValue(key)
    }

    /**
     * Delegates to the same custodian the Bluetooth module reaches through
     * [SecureIdentityPreferences.loadOrMintSigningKey]. There is exactly one of it per process,
     * and it is the only code in the tree that may create identity key material.
     */
    override fun loadOrMint(
        key: String,
        publicFormOf: (String) -> String,
        mint: () -> String,
    ): String = securePrefs.loadOrMintSecureValue(key, publicFormOf, mint)

    override fun removeKeys(vararg keys: String) {
        securePrefs.clearSecureValues(*keys)
    }

    override fun clearAll() {
        securePrefs.clearIdentityData()
    }

    override fun storeState(): IdentityStoreState = when (securePrefs.storeState()) {
        PreferenceStoreState.FIRST_RUN -> IdentityStoreState.FIRST_RUN
        PreferenceStoreState.POPULATED -> IdentityStoreState.POPULATED
        PreferenceStoreState.UNREADABLE -> IdentityStoreState.UNREADABLE
    }
}
