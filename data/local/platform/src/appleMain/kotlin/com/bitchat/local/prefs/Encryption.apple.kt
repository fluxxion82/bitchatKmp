package com.bitchat.local.prefs

import com.russhwolf.settings.Settings
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * The secure stores of an Apple build: one Keychain service per store name.
 *
 * @param readableWhileLocked saves items so that they can be read from the first unlock after a
 *   restart onwards, and moves the items older builds saved "when unlocked" to that class when a
 *   store is opened. iOS needs it: the mesh keeps running on a locked phone, and iOS starts the
 *   app in the background (Bluetooth state restoration) before anyone unlocks it. False leaves
 *   the class alone, which is right for the macOS file keychain: it has no such classes.
 */
class NativeEncryptionSettingsFactory internal constructor(
    val readableWhileLocked: Boolean,
    private val items: KeychainItems,
    private val log: (String) -> Unit,
) : EncryptionSettingsFactory {

    constructor(readableWhileLocked: Boolean) : this(readableWhileLocked, SecurityFrameworkKeychainItems, ::println)

    private val accessibility: KeychainAccessibility? =
        if (readableWhileLocked) KeychainAccessibility.AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY else null

    private val lock = SynchronizedObject()
    private val stores = mutableMapOf<String, AppleKeychainSettings>()

    /**
     * The one store of [name].
     *
     * @throws SecureStoreUnavailableException while the device is locked and the items of [name]
     *   are still to be moved to their class. Nothing is kept then, so the next call opens the
     *   store afresh.
     */
    override fun createEncrypted(name: String): Settings = synchronized(lock) {
        stores[name] ?: AppleKeychainSettings(name, items, accessibility, log).also {
            it.open()
            stores[name] = it
        }
    }
}
