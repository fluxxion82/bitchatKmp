@file:OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)

package com.bitchat.local.prefs

import com.bitchat.local.prefs.KeychainAccessibility.AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY
import com.bitchat.local.prefs.KeychainAccessibility.WHEN_UNLOCKED
import com.russhwolf.settings.Settings
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSKeyedArchiver
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.numberWithInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val SERVICE = "bitchat_identity"
private const val NOT_FOUND = -25300
private const val DUPLICATE = -25299
private const val LOCKED = -25308
private const val NOT_AVAILABLE = -25291
private const val PARAM = -50

/** The Keychain store against a Keychain that can be locked, scripted and inspected. */
class AppleKeychainSettingsTest {
    private val keychain = FakeKeychain()
    private val log = mutableListOf<String>()

    // The factory as iOS builds it, and with false as macOS does, over the fake Keychain.
    private fun factory(readableWhileLocked: Boolean = true) =
        NativeEncryptionSettingsFactory(readableWhileLocked, keychain, log::add)

    private fun store(readableWhileLocked: Boolean = true): Settings =
        factory(readableWhileLocked).createEncrypted(SERVICE)

    // --- what older builds saved is still there ---

    @Test
    fun `a value an older build saved is read as it was`() {
        keychain.saved("nostr_private_key", "aa11", WHEN_UNLOCKED)
        keychain.saved("launches", NSKeyedArchiver.archivedDataWithRootObject(NSNumber.numberWithInt(7), true, null)!!, WHEN_UNLOCKED)

        val store = store()

        assertEquals("aa11", store.getStringOrNull("nostr_private_key"))
        assertEquals(7, store.getIntOrNull("launches"))
    }

    @Test
    fun `nothing that looks for a value names a class`() {
        keychain.saved("old", "1", WHEN_UNLOCKED)
        val store = store()
        keychain.calls.clear()

        store.getStringOrNull("old")
        store.getStringOrNull("none")
        store.hasKey("old")
        store.keys
        store.putString("old", "2")
        store.putString("new", "3")
        (store as AddOnlySettings).putStringIfAbsent("old", "4")
        store.remove("old")
        store.clear()

        assertEquals(
            listOf("data", "data", "exists", "accounts", "add", "update", "add", "add", "delete", "accounts", "delete"),
            keychain.calls.map { it.name },
        )
        assertEquals(emptyList(), keychain.calls.filter { it.query.savedAs != null })
    }

    // --- the class of what is saved ---

    @Test
    fun `a factory told to keep items readable saves every new value as after first unlock on this device`() {
        val store = store()

        store.putString("signing_private_key", "k")
        store.putInt("count", 1)
        (store as AddOnlySettings).putStringIfAbsent("nostr_private_key", "n")

        assertEquals(
            listOf(AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY, AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY, AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY),
            keychain.calls.filter { it.name == "add" }.map { it.value?.accessibility },
        )
        assertEquals(AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY, keychain.classOf("signing_private_key"))
    }

    @Test
    fun `a value written over an existing one carries the class too`() {
        val store = store()
        store.putString("userName", "alice")
        keychain.setClass("userName", WHEN_UNLOCKED)

        store.putString("userName", "bob")

        assertEquals("bob", keychain.text("userName"))
        assertEquals(AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY, keychain.classOf("userName"))
    }

    @Test
    fun `a factory that is not told to keep items readable saves without a class and moves nothing`() {
        keychain.saved("old", "1", WHEN_UNLOCKED)

        val store = store(readableWhileLocked = false)
        store.putString("new", "2")
        store.putString("old", "3")

        assertEquals(listOf("add", "add", "update"), keychain.calls.map { it.name })
        assertEquals(emptyList(), keychain.calls.filter { it.value?.accessibility != null || it.query.savedAs != null })
        assertEquals(WHEN_UNLOCKED, keychain.classOf("old"))
    }

    // --- what an answer means ---

    @Test
    fun `only no such item means absent`() {
        val store = store()

        assertNull(store.getStringOrNull("userName"))
        assertNull(store.getIntOrNull("count"))
        assertFalse(store.hasKey("userName"))
        assertEquals(emptySet(), store.keys)
        assertEquals("fallback", store.getString("userName", "fallback"))
    }

    @Test
    fun `a locked Keychain is not an empty one`() {
        val store = store()
        store.putString("userName", "alice")

        for (operation in listOf("data", "exists", "accounts", "delete")) {
            keychain.answer(operation, LOCKED)
            assertFailsWith<SecureStoreUnavailableException>(operation) {
                when (operation) {
                    "data" -> store.getStringOrNull("userName")
                    "exists" -> store.hasKey("userName")
                    "accounts" -> store.keys
                    else -> store.remove("userName")
                }
            }
            // A failure makes the store look again for items to move with its next operation;
            // let it, so that the next answer set here goes to the operation under test.
            store.getStringOrNull("userName")
        }
        assertEquals("alice", keychain.text("userName"))
    }

    @Test
    fun `any other failure is not an empty Keychain either`() {
        val store = store()
        store.putString("userName", "alice")

        for (operation in listOf("data", "exists", "accounts", "delete")) {
            keychain.answer(operation, NOT_AVAILABLE)
            assertFailsWith<SecureStoreUnavailableException>(operation) {
                when (operation) {
                    "data" -> store.getStringOrNull("userName")
                    "exists" -> store.hasKey("userName")
                    "accounts" -> store.keys
                    else -> store.remove("userName")
                }
            }
            store.getStringOrNull("userName")
        }
        assertEquals("alice", keychain.text("userName"))
    }

    @Test
    fun `a failure of any kind makes the store look again for items to move`() {
        val store = store()
        store.putString("userName", "alice")
        keychain.calls.clear()

        keychain.answer("data", NOT_AVAILABLE)
        assertFailsWith<SecureStoreUnavailableException> { store.getStringOrNull("userName") }
        store.getStringOrNull("userName")
        store.getStringOrNull("userName")

        assertEquals(listOf("data", "exists", "data", "data"), keychain.calls.map { it.name })
        assertEquals(KeychainQuery(SERVICE, savedAs = WHEN_UNLOCKED), keychain.calls[1].query)
    }

    @Test
    fun `a value that is there and cannot be used is not absent`() {
        keychain.saved("userName", bytes(0xFF, 0xFE, 0xFD), AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY)
        keychain.saved("count", utf8("not an archive"), AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY)
        val store = store()

        assertFailsWith<SecureStoreUnavailableException> { store.getStringOrNull("userName") }
        assertFailsWith<SecureStoreUnavailableException> { store.getIntOrNull("count") }

        keychain.answerWithoutData = true
        assertFailsWith<SecureStoreUnavailableException> { store.getStringOrNull("userName") }
        keychain.answerWithoutData = false

        // The Keychain answered each time; what it returned was the trouble. That is no sign of
        // items left in the old class, and the store does not go looking for any.
        keychain.calls.clear()
        store.hasKey("userName")
        assertEquals(listOf("exists"), keychain.calls.map { it.name })
        assertEquals(KeychainQuery(SERVICE, "userName"), keychain.calls.single().query)
    }

    @Test
    fun `removing what is not there is not a failure`() {
        store().remove("never-saved")
    }

    // --- writing ---

    @Test
    fun `a write replaces what is there`() {
        val store = store()

        store.putString("userState", "active")
        store.putString("userState", "settings")

        assertEquals("settings", store.getStringOrNull("userState"))
        assertEquals(listOf("add", "add", "update"), keychain.calls.map { it.name }.filter { it == "add" || it == "update" })
    }

    @Test
    fun `a write that fails raises and leaves what was there`() {
        val store = store()
        store.putString("userName", "alice")

        keychain.answer("add", LOCKED)
        assertFailsWith<SecureStoreUnavailableException> { store.putString("other", "x") }
        keychain.unscript()
        keychain.answer("update", NOT_AVAILABLE)
        assertFailsWith<SecureStoreUnavailableException> { store.putString("userName", "bob") }

        assertEquals("alice", keychain.text("userName"))
        assertNull(keychain.textOrNull("other"))
    }

    @Test
    fun `an update that finds no item right after the add found one raises and leaves the item`() {
        // The add says the item is there; the update that follows says there is no such item.
        val store = store()
        store.putString("signing_public_key", "old")
        keychain.calls.clear()
        keychain.answer("update", NOT_FOUND)

        assertFailsWith<SecureStoreUnavailableException> { store.putString("signing_public_key", "new") }

        assertEquals("old", keychain.text("signing_public_key"))
        // And the store looks again for items it may not be shown, from its next operation on.
        store.getStringOrNull("signing_public_key")
        assertEquals(listOf("add", "update", "exists", "data"), keychain.calls.map { it.name })
    }

    @Test
    fun `adding where nothing is saved creates the value`() {
        val store = store() as AddOnlySettings

        assertTrue(store.putStringIfAbsent("nostr_private_key", "aa11"))

        assertEquals("aa11", keychain.text("nostr_private_key"))
    }

    @Test
    fun `adding never replaces a value that is there`() {
        keychain.saved("nostr_private_key", "aa11", WHEN_UNLOCKED)
        val store = store()
        keychain.calls.clear()

        assertFalse((store as AddOnlySettings).putStringIfAbsent("nostr_private_key", "bb22"))

        assertEquals("aa11", keychain.text("nostr_private_key"))
        assertEquals(listOf("add"), keychain.calls.map { it.name })
    }

    @Test
    fun `adding never replaces a value the Keychain said was absent`() {
        // The read is answered "no such item" although the item is there; the add finds it.
        keychain.saved("nostr_private_key", "aa11", AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY)
        val store = store()
        keychain.answer("data", NOT_FOUND)

        assertNull(store.getStringOrNull("nostr_private_key"))
        assertFalse((store as AddOnlySettings).putStringIfAbsent("nostr_private_key", "bb22"))

        assertEquals("aa11", keychain.text("nostr_private_key"))
    }

    @Test
    fun `an add that fails raises`() {
        val store = store() as AddOnlySettings
        keychain.answer("add", LOCKED)

        assertFailsWith<SecureStoreUnavailableException> { store.putStringIfAbsent("k", "v") }
    }

    @Test
    fun `numbers come back as they went in`() {
        val store = store()

        store.putInt("int", -7)
        store.putLong("long", 1L shl 40)
        store.putBoolean("boolean", true)
        store.putDouble("double", 1.5)
        store.putFloat("float", 2.5f)

        assertEquals(-7, store.getIntOrNull("int"))
        assertEquals(1L shl 40, store.getLongOrNull("long"))
        assertEquals(true, store.getBooleanOrNull("boolean"))
        assertEquals(1.5, store.getDoubleOrNull("double"))
        assertEquals(2.5f, store.getFloatOrNull("float"))
        assertEquals(setOf("int", "long", "boolean", "double", "float"), store.keys)
        assertEquals(5, store.size)
    }

    @Test
    fun `clearing removes every value of this service and no other`() {
        keychain.saved("other", "x", AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY, service = "userPreferences")
        val store = store()
        store.putString("a", "1")
        store.putString("b", "2")

        store.clear()

        assertEquals(emptySet(), store.keys)
        assertEquals("x", keychain.text("other", service = "userPreferences"))
    }

    // --- moving what older builds saved ---

    @Test
    fun `a store with nothing saved as when unlocked is opened without a change`() {
        keychain.saved("signing_private_key", "k", AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY)

        val store = store()
        store.getStringOrNull("signing_private_key")
        store.getStringOrNull("signing_private_key")

        assertEquals(listOf("exists", "data", "data"), keychain.calls.map { it.name })
        assertEquals(KeychainQuery(SERVICE, savedAs = WHEN_UNLOCKED), keychain.calls.first().query)
        assertEquals(listOf("Keychain: '$SERVICE' is readable while the phone is locked"), log)
    }

    @Test
    fun `what an older build saved is moved in one call that touches no value`() {
        keychain.saved("signing_private_key", "k", WHEN_UNLOCKED)
        keychain.saved("nostr_private_key", "n", WHEN_UNLOCKED)
        keychain.saved("nostr_device_seed", "s", AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY)
        keychain.saved("userName", "alice", WHEN_UNLOCKED, service = "userPreferences")

        val store = store()

        val moves = keychain.calls.filter { it.name == "update" }
        assertEquals(1, moves.size)
        assertEquals(KeychainQuery(SERVICE), moves.single().query)
        assertNull(moves.single().value?.data)
        assertEquals(AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY, moves.single().value?.accessibility)
        assertEquals(setOf(AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY), keychain.classesOf(SERVICE))
        assertEquals(WHEN_UNLOCKED, keychain.classOf("userName", service = "userPreferences"))
        assertEquals("k", store.getStringOrNull("signing_private_key"))
        assertEquals("n", store.getStringOrNull("nostr_private_key"))
        assertEquals(listOf("Keychain: '$SERVICE' items moved; readable while the phone is locked"), log)

        // Done for this process: nothing is asked again.
        keychain.calls.clear()
        store.getStringOrNull("signing_private_key")
        assertEquals(listOf("data"), keychain.calls.map { it.name })
    }

    @Test
    fun `a store with items still to move does not open on a locked device`() {
        keychain.saved("signing_private_key", "k", WHEN_UNLOCKED)
        keychain.locked = true
        val factory = factory()

        assertFailsWith<SecureStoreUnavailableException> { factory.createEncrypted(SERVICE) }
        // Nothing was kept from the first try: the second is refused in the same way.
        assertFailsWith<SecureStoreUnavailableException> { factory.createEncrypted(SERVICE) }

        assertEquals(WHEN_UNLOCKED, keychain.classOf("signing_private_key"))
        assertEquals("k", keychain.text("signing_private_key"))

        keychain.locked = false
        val store = factory.createEncrypted(SERVICE)
        assertEquals("k", store.getStringOrNull("signing_private_key"))
        assertEquals(AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY, keychain.classOf("signing_private_key"))
    }

    @Test
    fun `a locked device with nothing left to move opens and reads`() {
        keychain.saved("signing_private_key", "k", AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY)
        keychain.locked = true

        assertEquals("k", store().getStringOrNull("signing_private_key"))
    }

    @Test
    fun `a move that says there is nothing while an old item is in plain sight is a locked device`() {
        // The call that moves skips what it cannot decrypt and reports that it found no item.
        keychain.saved("userState", "active", WHEN_UNLOCKED)
        keychain.answer("update", NOT_FOUND)

        assertFailsWith<SecureStoreUnavailableException> { store() }
    }

    @Test
    fun `a refused move is a locked device whatever the look afterwards says`() {
        // The move is refused; the look that follows wrongly finds nothing left.
        keychain.saved("userState", "active", WHEN_UNLOCKED)
        keychain.answer("exists", 0, NOT_FOUND)
        keychain.answer("update", LOCKED)

        assertFailsWith<SecureStoreUnavailableException> { store() }

        assertEquals(WHEN_UNLOCKED, keychain.classOf("userState"))
    }

    @Test
    fun `a refused look before the move is a locked device whatever follows`() {
        // The first look is refused; the move then says done and the second look finds nothing.
        keychain.saved("userState", "active", WHEN_UNLOCKED)
        keychain.answer("exists", LOCKED, NOT_FOUND)
        keychain.answer("update", 0)

        assertFailsWith<SecureStoreUnavailableException> { store() }
    }

    @Test
    fun `a refused look after the move is a locked device whatever came before`() {
        keychain.saved("userState", "active", WHEN_UNLOCKED)
        keychain.answer("exists", 0, LOCKED)
        keychain.answer("update", 0)

        assertFailsWith<SecureStoreUnavailableException> { store() }
    }

    @Test
    fun `a move that found nothing while an old item is seen afterwards is a locked device`() {
        // The first look fails for a reason of its own; the item shows in the second.
        keychain.saved("userState", "active", WHEN_UNLOCKED)
        keychain.answer("exists", PARAM, 0)
        keychain.answer("update", NOT_FOUND)

        assertFailsWith<SecureStoreUnavailableException> { store() }
    }

    @Test
    fun `a move that found nothing after an old item was seen is a locked device`() {
        // Seen before, skipped by the move, and not shown by the look afterwards.
        keychain.saved("userState", "active", WHEN_UNLOCKED)
        keychain.answer("exists", 0, NOT_FOUND)
        keychain.answer("update", NOT_FOUND)

        assertFailsWith<SecureStoreUnavailableException> { store() }
    }

    @Test
    fun `a move that failed is not done because nothing is seen afterwards`() {
        keychain.saved("userState", "active", WHEN_UNLOCKED)
        keychain.answer("exists", 0, NOT_FOUND)
        keychain.answer("update", PARAM)

        val store = store()

        assertEquals(WHEN_UNLOCKED, keychain.classOf("userState"))
        assertTrue(log.single().contains("could not be moved (unexpected"), log.toString())
        // Still owed: the next operation looks again and moves it.
        store.getStringOrNull("userState")
        assertEquals(AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY, keychain.classOf("userState"))
    }

    @Test
    fun `a move that says done while an old item is still seen leaves it owed`() {
        keychain.saved("userState", "active", WHEN_UNLOCKED)
        keychain.ignoreMoves = true

        val store = store()

        assertEquals("active", store.getStringOrNull("userState"))
        assertEquals(1, log.size, log.toString())
        assertTrue(log.single().contains("could not be moved (unexpected"), log.single())
        assertEquals(WHEN_UNLOCKED, keychain.classOf("userState"))

        // Still owed: once a move takes, the next operation makes it.
        keychain.ignoreMoves = false
        store.getStringOrNull("userState")
        assertEquals(AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY, keychain.classOf("userState"))
    }

    @Test
    fun `a move that fails for another reason opens the store and is tried again with every operation`() {
        keychain.saved("userState", "active", WHEN_UNLOCKED)
        keychain.answer("update", PARAM, PARAM)

        val store = store()
        assertEquals("active", store.getStringOrNull("userState"))
        assertEquals(WHEN_UNLOCKED, keychain.classOf("userState"))
        assertEquals(1, log.size, "said once: $log")

        assertEquals("active", store.getStringOrNull("userState"))
        assertEquals(AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY, keychain.classOf("userState"))
        assertEquals(2, log.size, log.toString())
    }

    @Test
    fun `a store that finds the device locked with items still to move refuses every operation`() {
        // Opened although an item could not be moved; then the phone locks.
        keychain.saved("userState", "active", WHEN_UNLOCKED)
        keychain.saved("fresh", "f", AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY)
        keychain.answer("update", PARAM)
        val store = store()
        keychain.locked = true
        keychain.calls.clear()

        val operations = listOf<Pair<String, () -> Any?>>(
            "read" to { store.getStringOrNull("fresh") },
            "look" to { store.hasKey("fresh") },
            "list" to { store.keys },
            "write" to { store.putString("fresh", "g") },
            "add" to { (store as AddOnlySettings).putStringIfAbsent("new", "n") },
            "delete" to { store.remove("fresh") },
        )
        for ((name, operation) in operations) {
            assertFailsWith<SecureStoreUnavailableException>(name) { operation() }
        }

        // Only the move was attempted: nothing was asked about a value, and nothing changed.
        assertEquals(setOf("exists", "update"), keychain.calls.map { it.name }.toSet())
        assertEquals("f", keychain.text("fresh"))
        assertNull(keychain.textOrNull("new"))
    }

    @Test
    fun `a read on a device known to be locked is not answered absent`() {
        // The store was opened when nothing showed in the old class. Then the signing key turns
        // out to be unreadable, and on the next try the Keychain, still locked, says of the same
        // key that there is no such item.
        val store = store()
        keychain.saved("signing_private_key", "k", WHEN_UNLOCKED)
        keychain.locked = true
        assertFailsWith<SecureStoreUnavailableException> { store.getStringOrNull("signing_private_key") }

        keychain.answer("data", NOT_FOUND)
        assertFailsWith<SecureStoreUnavailableException> { store.getStringOrNull("signing_private_key") }

        keychain.unscript()
        keychain.locked = false
        assertEquals("k", store.getStringOrNull("signing_private_key"))
    }

    @Test
    fun `a move that fails for a reason other than the lock does not stop the operation`() {
        keychain.saved("userState", "active", WHEN_UNLOCKED)
        keychain.saved("fresh", "f", AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY)
        keychain.answer("update", PARAM, PARAM)
        val store = store()

        assertEquals("f", store.getStringOrNull("fresh"))
    }

    @Test
    fun `an operation refused as locked makes the store look again for what is left to move`() {
        // Opened clean; later an item of the old class turns up (an older build ran in between,
        // or the first look did not show it).
        val store = store()
        keychain.saved("userState", "active", WHEN_UNLOCKED)
        keychain.locked = true

        assertFailsWith<SecureStoreUnavailableException> { store.getStringOrNull("userState") }

        keychain.locked = false
        assertEquals("active", store.getStringOrNull("userState"))
        assertEquals(AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY, keychain.classOf("userState"))
    }

    // --- the factory ---

    @Test
    fun `there is one store per service`() {
        val factory = factory()

        assertSame(factory.createEncrypted("userPreferences"), factory.createEncrypted("userPreferences"))
        assertNotSame(factory.createEncrypted("userPreferences"), factory.createEncrypted("block_list_prefs"))
    }
}

/**
 * Generic passwords by service and account, each with its class. Locked, it refuses what needs an
 * item's key (reading or rewriting an item saved "when unlocked") and still answers what does not.
 */
private class FakeKeychain : KeychainItems {
    class Call(val name: String, val query: KeychainQuery, val value: KeychainValue? = null) {
        override fun toString() = "$name($query)"
    }

    private class Item(var data: NSData, var accessibility: KeychainAccessibility)

    private val items = LinkedHashMap<Pair<String, String>, Item>()
    private val scripted = mutableMapOf<String, ArrayDeque<Int>>()
    val calls = mutableListOf<Call>()

    var locked = false

    /** A move over the whole service reports success and changes nothing. */
    var ignoreMoves = false

    /** A read reports success and hands back no data. */
    var answerWithoutData = false

    fun saved(account: String, text: String, accessibility: KeychainAccessibility, service: String = SERVICE) =
        saved(account, utf8(text), accessibility, service)

    fun saved(account: String, data: NSData, accessibility: KeychainAccessibility, service: String = SERVICE) {
        items[service to account] = Item(data, accessibility)
    }

    fun textOrNull(account: String, service: String = SERVICE): String? =
        items[service to account]?.let { NSString.create(it.data, NSUTF8StringEncoding) as String? }

    fun text(account: String, service: String = SERVICE): String = textOrNull(account, service) ?: error("no $account")
    fun classOf(account: String, service: String = SERVICE) = items.getValue(service to account).accessibility
    fun classesOf(service: String) = items.filterKeys { it.first == service }.values.map { it.accessibility }.toSet()
    fun setClass(account: String, accessibility: KeychainAccessibility) {
        items.getValue(SERVICE to account).accessibility = accessibility
    }

    /** The next calls of [operation] answer with [statuses], in turn, whatever is saved. */
    fun answer(operation: String, vararg statuses: Int) {
        scripted.getOrPut(operation) { ArrayDeque() }.addAll(statuses.toList())
    }

    fun unscript() = scripted.clear()

    private fun scriptedAnswer(operation: String): Int? = scripted[operation]?.removeFirstOrNull()

    private fun matching(query: KeychainQuery) = items.filter { (name, item) ->
        name.first == query.service &&
            (query.account == null || name.second == query.account) &&
            (query.savedAs == null || item.accessibility == query.savedAs)
    }

    private fun Item.needsUnlock() = locked && accessibility == WHEN_UNLOCKED

    override fun add(item: KeychainQuery, value: KeychainValue): Int {
        calls += Call("add", item, value)
        scriptedAnswer("add")?.let { return it }
        val name = item.service to requireNotNull(item.account)
        if (name in items) return DUPLICATE
        // Saved with no class, an item is "when unlocked": the system's default.
        items[name] = Item(requireNotNull(value.data), value.accessibility ?: WHEN_UNLOCKED)
        return 0
    }

    override fun data(item: KeychainQuery): KeychainData {
        calls += Call("data", item)
        scriptedAnswer("data")?.let { return KeychainData(it, null) }
        val found = matching(item).values.firstOrNull() ?: return KeychainData(NOT_FOUND, null)
        if (found.needsUnlock()) return KeychainData(LOCKED, null)
        return KeychainData(0, if (answerWithoutData) null else found.data)
    }

    override fun exists(items: KeychainQuery): Int {
        calls += Call("exists", items)
        scriptedAnswer("exists")?.let { return it }
        return if (matching(items).isEmpty()) NOT_FOUND else 0
    }

    override fun accounts(items: KeychainQuery): KeychainAccounts {
        calls += Call("accounts", items)
        scriptedAnswer("accounts")?.let { return KeychainAccounts(it, emptySet()) }
        val found = matching(items)
        return if (found.isEmpty()) KeychainAccounts(NOT_FOUND, emptySet()) else KeychainAccounts(0, found.keys.map { it.second }.toSet())
    }

    override fun update(items: KeychainQuery, changes: KeychainValue): Int {
        calls += Call("update", items, changes)
        scriptedAnswer("update")?.let { return it }
        val found = matching(items).values
        if (found.isEmpty()) return NOT_FOUND
        if (found.any { it.needsUnlock() }) return LOCKED
        if (items.account == null && ignoreMoves) return 0
        for (item in found) {
            changes.data?.let { item.data = it }
            changes.accessibility?.let { item.accessibility = it }
        }
        return 0
    }

    override fun delete(items: KeychainQuery): Int {
        calls += Call("delete", items)
        scriptedAnswer("delete")?.let { return it }
        val found = matching(items).keys
        if (found.isEmpty()) return NOT_FOUND
        found.forEach(this.items::remove)
        return 0
    }

    override fun describe(status: Int): String = "status text"
}

private fun utf8(text: String): NSData = requireNotNull((text as NSString).dataUsingEncoding(NSUTF8StringEncoding))

private fun bytes(vararg values: Int): NSData {
    val text = values.joinToString("") { it.toChar().toString() }
    // Latin-1 keeps each value as one byte, which as UTF-8 is not text.
    return requireNotNull((text as NSString).dataUsingEncoding(platform.Foundation.NSISOLatin1StringEncoding))
}
