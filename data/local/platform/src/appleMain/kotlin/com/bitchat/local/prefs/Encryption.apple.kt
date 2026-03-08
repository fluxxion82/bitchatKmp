package com.bitchat.local.prefs

import com.russhwolf.settings.ExperimentalSettingsImplementation
import com.russhwolf.settings.KeychainSettings
import com.russhwolf.settings.Settings

class NativeEncryptionSettingsFactory : EncryptionSettingsFactory {
    @OptIn(ExperimentalSettingsImplementation::class)
    override fun createEncrypted(name: String): Settings {
        return KeychainSettings(service = name)
    }
}
