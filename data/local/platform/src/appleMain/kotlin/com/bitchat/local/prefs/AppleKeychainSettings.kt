@file:OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)

package com.bitchat.local.prefs

import com.russhwolf.settings.Settings
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSKeyedArchiver
import platform.Foundation.NSKeyedUnarchiver
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.numberWithBool
import platform.Foundation.numberWithDouble
import platform.Foundation.numberWithFloat
import platform.Foundation.numberWithInt
import platform.Foundation.numberWithLongLong
import platform.Security.errSecDuplicateItem
import platform.Security.errSecInteractionNotAllowed
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess

/**
 * One Keychain service as a key-value store: a generic password per key, the key as its account.
 *
 * It keeps the item layout of multiplatform-settings' `KeychainSettings`, which wrote every item
 * that exists on a device today (strings as UTF-8, numbers as an archived `NSNumber`), and differs
 * from it in what this app cannot do without:
 *
 * **The class of an item.** The library saves items "when unlocked", so nothing can be read from
 * the moment the phone locks, and it puts whatever attributes it is given into every search as
 * well, so it cannot be told to save in another class without losing sight of the items it has.
 * Here an add and an update carry [accessibility]; nothing that looks for a value does.
 *
 * **What a status means.** "No such item" is the one answer that means absent. Every other
 * failure raises [SecureStoreUnavailableException]: a locked Keychain is not an empty one. A value
 * that is there and cannot be decoded raises too.
 *
 * **Adding without replacing.** [putStringIfAbsent] does the add alone and says whether there was
 * an item already; see [AddOnlySettings] for who needs it. Every other write replaces, as the
 * library's did: added, or updated when it exists.
 */
internal class AppleKeychainSettings(
    private val service: String,
    private val items: KeychainItems,
    private val accessibility: KeychainAccessibility?,
    private val log: (String) -> Unit,
) : Settings, AddOnlySettings {
    // Whether items an older build saved may still be in its class.
    private val moveOwed = atomic(accessibility != null)
    private val lastOwedReport = atomic("")

    private enum class Move { DONE, LOCKED, OWED }

    /**
     * Moves what older builds saved to [accessibility], and raises while the device is locked
     * and something is left to move.
     *
     * The factory calls it before it hands the store out. A store with items still "when
     * unlocked" on a locked phone would fail on every read, at whichever caller came first; not
     * opening at all puts that failure in one known place, the start, which tries again.
     * It is the rule of every operation, applied before there is one to make.
     */
    fun open() = operation { }

    /**
     * Done is established by looking, not by the answer of the call that moves alone: the
     * Keychain is asked whether any item of this service is still saved "when unlocked", before
     * the move (most starts find none and change nothing) and again after it. After a move, done
     * takes all three answers: neither look was refused, the move said it succeeded, and the
     * look afterwards finds nothing left. One answer that says otherwise is not outvoted by
     * another that may be wrong; the next attempt starts from a look that finds nothing.
     */
    private fun moveItemsToTheirClass(): Move {
        val target = accessibility ?: return Move.DONE
        if (!moveOwed.value) return Move.DONE

        val leftBehind = KeychainQuery(service, savedAs = KeychainAccessibility.WHEN_UNLOCKED)
        val before = items.exists(leftBehind)
        if (before == errSecItemNotFound) return done("is readable while the phone is locked")

        // Every item of the service, whatever its class: one call, no value read or rewritten.
        val moved = items.update(KeychainQuery(service), KeychainValue(accessibility = target))
        val after = items.exists(leftBehind)

        // Only a locked device explains a refusal, and only one explains a move that found
        // nothing to do while an item of the old class was in plain sight.
        val locked = errSecInteractionNotAllowed in listOf(before, moved, after) ||
            (moved == errSecItemNotFound && (before == errSecSuccess || after == errSecSuccess))
        if (!locked && moved == errSecSuccess && after == errSecItemNotFound) {
            return done("items moved; readable while the phone is locked")
        }
        val report = "Keychain: '$service' has items readable only when unlocked that could not be moved " +
            "(${if (locked) "the device is locked" else "unexpected"}: " +
            "left before ${status(before)}, move ${status(moved)}, left after ${status(after)})"
        // Said once per outcome, not once per operation.
        if (lastOwedReport.getAndSet(report) != report) log(report)
        return if (locked) Move.LOCKED else Move.OWED
    }

    private fun done(what: String): Move {
        if (moveOwed.compareAndSet(true, false)) log("Keychain: '$service' $what")
        return Move.DONE
    }

    /**
     * Every operation first tries the move, if it is still owed. A device found locked with
     * items left to move ends the operation there: whatever the Keychain answered next about an
     * item it cannot decrypt would be taken at its word, "not found" included. A move that
     * failed for any other reason does not stop the operation.
     */
    private inline fun <T> operation(block: () -> T): T {
        if (moveItemsToTheirClass() == Move.LOCKED) {
            throw SecureStoreUnavailableException(
                "Keychain '$service': the device is locked and items are still saved as readable only when unlocked",
            )
        }
        return block()
    }

    override val keys: Set<String>
        get() = operation {
            val answer = items.accounts(KeychainQuery(service))
            when (answer.status) {
                errSecSuccess -> answer.accounts
                errSecItemNotFound -> emptySet()
                else -> throw unavailable("listing", answer.status)
            }
        }

    override val size: Int get() = keys.size

    override fun clear(): Unit = keys.forEach { remove(it) }

    override fun remove(key: String): Unit = operation {
        when (val status = items.delete(KeychainQuery(service, key))) {
            errSecSuccess, errSecItemNotFound -> Unit
            else -> throw unavailable("deleting '$key'", status)
        }
    }

    override fun hasKey(key: String): Boolean = operation {
        when (val status = items.exists(KeychainQuery(service, key))) {
            errSecSuccess -> true
            errSecItemNotFound -> false
            else -> throw unavailable("looking for '$key'", status)
        }
    }

    private fun read(key: String): NSData? = operation {
        val answer = items.data(KeychainQuery(service, key))
        when (answer.status) {
            errSecSuccess -> answer.data ?: throw unusable(key, "the Keychain returned no data for it")
            errSecItemNotFound -> null
            else -> throw unavailable("reading '$key'", answer.status)
        }
    }

    private fun write(key: String, data: NSData?): Unit = operation {
        val value = KeychainValue(data ?: throw unusable(key, "its new value could not be encoded"), accessibility)
        when (val added = items.add(KeychainQuery(service, key), value)) {
            errSecSuccess -> Unit
            errSecDuplicateItem -> {
                val updated = items.update(KeychainQuery(service, key), value)
                if (updated != errSecSuccess) throw unavailable("updating '$key', which the add found there,", updated)
            }

            else -> throw unavailable("adding '$key'", added)
        }
    }

    override fun putStringIfAbsent(key: String, value: String): Boolean = operation {
        val data = utf8(value) ?: throw unusable(key, "its new value could not be encoded")
        when (val added = items.add(KeychainQuery(service, key), KeychainValue(data, accessibility))) {
            errSecSuccess -> true
            errSecDuplicateItem -> false
            else -> throw unavailable("adding '$key'", added)
        }
    }

    private fun unavailable(doing: String, status: Int): SecureStoreUnavailableException {
        // Whatever went wrong, an item of the old class that the look in open() did not show is
        // one explanation: look again from the next operation on.
        if (accessibility != null) moveOwed.value = true
        return SecureStoreUnavailableException("Keychain '$service': $doing failed with ${status(status)}")
    }

    private fun unusable(key: String, why: String) =
        SecureStoreUnavailableException("Keychain '$service': '$key' is there and unusable: $why")

    private fun status(status: Int): String = "status $status (${items.describe(status)})"

    override fun putString(key: String, value: String): Unit = write(key, utf8(value))

    override fun getString(key: String, defaultValue: String): String = getStringOrNull(key) ?: defaultValue

    override fun getStringOrNull(key: String): String? = read(key)?.let { data ->
        NSString.create(data, NSUTF8StringEncoding)?.toKString() ?: throw unusable(key, "it is not UTF-8 text")
    }

    override fun putInt(key: String, value: Int): Unit = write(key, archived(NSNumber.numberWithInt(value)))
    override fun getInt(key: String, defaultValue: Int): Int = getIntOrNull(key) ?: defaultValue
    override fun getIntOrNull(key: String): Int? = number(key)?.intValue

    override fun putLong(key: String, value: Long): Unit = write(key, archived(NSNumber.numberWithLongLong(value)))
    override fun getLong(key: String, defaultValue: Long): Long = getLongOrNull(key) ?: defaultValue
    override fun getLongOrNull(key: String): Long? = number(key)?.longLongValue

    override fun putFloat(key: String, value: Float): Unit = write(key, archived(NSNumber.numberWithFloat(value)))
    override fun getFloat(key: String, defaultValue: Float): Float = getFloatOrNull(key) ?: defaultValue
    override fun getFloatOrNull(key: String): Float? = number(key)?.floatValue

    override fun putDouble(key: String, value: Double): Unit = write(key, archived(NSNumber.numberWithDouble(value)))
    override fun getDouble(key: String, defaultValue: Double): Double = getDoubleOrNull(key) ?: defaultValue
    override fun getDoubleOrNull(key: String): Double? = number(key)?.doubleValue

    override fun putBoolean(key: String, value: Boolean): Unit = write(key, archived(NSNumber.numberWithBool(value)))
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = getBooleanOrNull(key) ?: defaultValue
    override fun getBooleanOrNull(key: String): Boolean? = number(key)?.boolValue

    private fun number(key: String): NSNumber? = read(key)?.let { data ->
        // Not unarchiveObjectWithData: that one raises an Objective-C exception on data that is
        // no archive, which nothing here could take.
        NSKeyedUnarchiver.unarchivedObjectOfClass(NSNumber, fromData = data, error = null) as? NSNumber
            ?: throw unusable(key, "it is not a number")
    }

    private fun utf8(text: String): NSData? = text.toNSString().dataUsingEncoding(NSUTF8StringEncoding)

    private fun archived(number: NSNumber): NSData? = NSKeyedArchiver.archivedDataWithRootObject(number, true, null)
}

// Kotlin strings and NSString are the same object to the runtime; the casts only say so.
@Suppress("CAST_NEVER_SUCCEEDS")
private fun String.toNSString() = this as NSString

@Suppress("CAST_NEVER_SUCCEEDS")
private fun NSString.toKString() = this as String
