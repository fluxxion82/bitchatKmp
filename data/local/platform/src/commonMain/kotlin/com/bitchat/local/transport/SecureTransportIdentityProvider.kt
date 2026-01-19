package com.bitchat.local.transport

import com.bitchat.local.prefs.SecureIdentityPreferences
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

    override fun removeKeys(vararg keys: String) {
        securePrefs.clearSecureValues(*keys)
    }

    override fun clearAll() {
        securePrefs.clearIdentityData()
    }
}
