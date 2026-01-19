package com.bitchat.local.prefs

import com.russhwolf.settings.Settings

interface EncryptionSettingsFactory {
    fun createEncrypted(name: String): Settings
}
