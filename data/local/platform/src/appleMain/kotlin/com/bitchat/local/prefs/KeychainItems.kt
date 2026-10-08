@file:OptIn(ExperimentalForeignApi::class)

package com.bitchat.local.prefs

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Security.SecCopyErrorMessageString
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecDecode
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccessibleWhenUnlocked
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitAll
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnAttributes
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/** When the system will decrypt a Keychain item: its `kSecAttrAccessible` class. */
internal enum class KeychainAccessibility {
    /**
     * Only while the device is unlocked. The class of an item saved without one, which is how
     * multiplatform-settings saved every item of the builds before this store. Nothing is saved
     * in it any more; it is only ever looked for, to find what is still to be moved.
     */
    WHEN_UNLOCKED,

    /**
     * From the first unlock after a restart until the next restart, on this device only. What a
     * mesh that keeps running on a locked phone needs, and what upstream iOS uses.
     */
    AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY,
}

/**
 * What names items: every generic password of [service], or the one under [account].
 *
 * @param savedAs narrows it to the items saved in that class. Null, which is what every read,
 *   check, listing, delete and update of this app's values uses, finds an item whatever class it
 *   is in. A class in a search hides every item of another class behind "not found", so the only
 *   search that names one is the one that asks whether anything is left in the old class.
 */
internal data class KeychainQuery(
    val service: String,
    val account: String? = null,
    val savedAs: KeychainAccessibility? = null,
)

/** What an add gives an item, or an update changes in it; null leaves that part alone. */
internal class KeychainValue(val data: NSData? = null, val accessibility: KeychainAccessibility? = null)

internal class KeychainData(val status: Int, val data: NSData?)

internal class KeychainAccounts(val status: Int, val accounts: Set<String>)

/**
 * `SecItem*` for this app's generic passwords: each call takes a description of the dictionaries
 * it is made with and answers with the `OSStatus` it got. Nothing is decided here;
 * [AppleKeychainSettings] decides, and a test can see every description it hands over.
 */
internal interface KeychainItems {
    fun add(item: KeychainQuery, value: KeychainValue): Int
    fun data(item: KeychainQuery): KeychainData
    fun exists(items: KeychainQuery): Int
    fun accounts(items: KeychainQuery): KeychainAccounts

    /** Applies [changes] to every item [items] names, in one call. */
    fun update(items: KeychainQuery, changes: KeychainValue): Int
    fun delete(items: KeychainQuery): Int

    /** The system's words for [status], for a log line. */
    fun describe(status: Int): String
}

/**
 * The real thing: a description turned into a dictionary, and the call. No host test runs this;
 * see the simulator and device checks.
 */
internal object SecurityFrameworkKeychainItems : KeychainItems {

    override fun add(item: KeychainQuery, value: KeychainValue): Int =
        dictionary(item).use { attributes ->
            attributes.put(value)
            SecItemAdd(attributes.dictionary, null)
        }

    override fun data(item: KeychainQuery): KeychainData = memScoped {
        val result = alloc<CFTypeRefVar>()
        val status = dictionary(item).use { query ->
            query.set(kSecReturnData, kCFBooleanTrue)
            query.set(kSecMatchLimit, kSecMatchLimitOne)
            SecItemCopyMatching(query.dictionary, result.ptr)
        }
        if (status != errSecSuccess) return KeychainData(status, null)
        KeychainData(status, CFBridgingRelease(result.value) as? NSData)
    }

    override fun exists(items: KeychainQuery): Int =
        dictionary(items).use { query ->
            query.set(kSecMatchLimit, kSecMatchLimitOne)
            SecItemCopyMatching(query.dictionary, null)
        }

    override fun accounts(items: KeychainQuery): KeychainAccounts = memScoped {
        val result = alloc<CFTypeRefVar>()
        val status = dictionary(items).use { query ->
            query.set(kSecReturnAttributes, kCFBooleanTrue)
            query.set(kSecMatchLimit, kSecMatchLimitAll)
            SecItemCopyMatching(query.dictionary, result.ptr)
        }
        if (status != errSecSuccess) return KeychainAccounts(status, emptySet())

        // A listing that cannot be read in full is not a short listing: an account left out here
        // would be an item the caller takes for absent.
        val found = CFBridgingRelease(result.value) as? List<*> ?: return KeychainAccounts(errSecDecode, emptySet())
        val accountAttribute = CFBridgingRelease(CFRetain(kSecAttrAccount)) as? String
            ?: return KeychainAccounts(errSecDecode, emptySet())
        val accounts = found.map { item ->
            (item as? Map<*, *>)?.get(accountAttribute) as? String ?: return KeychainAccounts(errSecDecode, emptySet())
        }
        KeychainAccounts(status, accounts.toSet())
    }

    override fun update(items: KeychainQuery, changes: KeychainValue): Int =
        dictionary(items).use { query ->
            Attributes().use { attributes ->
                attributes.put(changes)
                SecItemUpdate(query.dictionary, attributes.dictionary)
            }
        }

    override fun delete(items: KeychainQuery): Int =
        dictionary(items).use { query -> SecItemDelete(query.dictionary) }

    override fun describe(status: Int): String =
        CFBridgingRelease(SecCopyErrorMessageString(status, null)) as? String ?: "unknown"

    private fun dictionary(items: KeychainQuery): Attributes = Attributes().also {
        it.set(kSecClass, kSecClassGenericPassword)
        it.setObject(kSecAttrService, items.service)
        if (items.account != null) it.setObject(kSecAttrAccount, items.account)
        if (items.savedAs != null) it.set(kSecAttrAccessible, items.savedAs.constant)
    }

    private fun Attributes.put(value: KeychainValue) {
        if (value.data != null) setObject(kSecValueData, value.data)
        if (value.accessibility != null) set(kSecAttrAccessible, value.accessibility.constant)
    }

    private val KeychainAccessibility.constant: CFStringRef?
        get() = when (this) {
            KeychainAccessibility.WHEN_UNLOCKED -> kSecAttrAccessibleWhenUnlocked
            KeychainAccessibility.AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY -> kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        }

    /** A CoreFoundation dictionary that keeps what is put into it alive until it is released. */
    private class Attributes {
        val dictionary: CFMutableDictionaryRef? =
            CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)

        fun set(key: CFStringRef?, value: CFTypeRef?) = CFDictionarySetValue(dictionary, key, value)

        /** For a Kotlin or Foundation object: the dictionary takes its own reference. */
        fun setObject(key: CFStringRef?, value: Any) {
            val reference = CFBridgingRetain(value)
            CFDictionarySetValue(dictionary, key, reference)
            CFRelease(reference)
        }

        inline fun <T> use(block: (Attributes) -> T): T = try {
            block(this)
        } finally {
            CFRelease(dictionary)
        }
    }
}
