package com.bitchat.local.prefs

import com.russhwolf.settings.Settings

/**
 * A secure store that can save a value without ever replacing one.
 *
 * For a store whose "there is nothing under this key" can be wrong. The Keychain answers each
 * read on its own, and nothing here can prove that an item it cannot decrypt is always reported
 * as a failure and never as "not found". A writer that found a key absent and now creates its
 * first value - a random nickname, a new identity key, a list started from nothing - would, with
 * an ordinary write, replace whatever was there all along. With this one it cannot: the add either
 * creates the item or reports that one exists, and what exists stays.
 *
 * The stores that load everything into one map (Android, desktop, the embedded build) do not
 * implement it: there a read and a write see the same values.
 */
interface AddOnlySettings {
    /**
     * Saves [value] under [key] only if nothing is saved there.
     *
     * @return false when something is; nothing was changed.
     */
    fun putStringIfAbsent(key: String, value: String): Boolean
}

/**
 * The write of a caller that has just read [key] and was told there is nothing: never over an
 * existing value where the store can tell ([AddOnlySettings]), a plain write everywhere else.
 *
 * @return false when the store holds a value under [key] after all; nothing was changed.
 */
fun Settings.putStringWhereNoneWasRead(key: String, value: String): Boolean =
    if (this is AddOnlySettings) {
        putStringIfAbsent(key, value)
    } else {
        putString(key, value)
        true
    }

/** What a read found for a caller that is going to write the key back. */
internal class SavedText(
    val text: String?,
    /** The store answered that nothing is saved; the write back must then not replace anything. */
    val absent: Boolean,
)

/**
 * Reads [key] for a read-then-write.
 *
 * A store that did not answer raises: a value built from nothing must not be written over what
 * is there. Any other failure of the read counts as an empty value that the write replaces, which
 * is what these writers did before this existed and is left alone.
 */
internal fun Settings.readForUpdate(key: String): SavedText = try {
    val text = getStringOrNull(key)
    SavedText(text, absent = text == null)
} catch (e: SecureStoreUnavailableException) {
    throw e
} catch (e: Exception) {
    SavedText(null, absent = false)
}

/** Writes [value] back for the caller that read [saved]: over a value it read, never over one it was not shown. */
internal fun Settings.writeUpdate(key: String, saved: SavedText, value: String) {
    if (!saved.absent) {
        putString(key, value)
    } else if (!putStringWhereNoneWasRead(key, value)) {
        throw SecureStoreUnavailableException(
            "the secure store reported '$key' absent and holds it after all; what was about to " +
                "be written was made without it and is not written over it",
        )
    }
}
