package com.bitchat.local.prefs

/**
 * The secure store did not answer: it could not be read or written just now, what it holds under
 * a key could not be made sense of, or it contradicted itself (an item it reported absent turned
 * out to be there).
 *
 * On Apple this is any Keychain status that is neither success nor "no such item"; above all the
 * Keychain refusing while the device is locked (`errSecInteractionNotAllowed`: before the first
 * unlock after a reboot, and for items an older build saved as "when unlocked").
 *
 * It is never "no value". A caller that would create or overwrite something when a key is absent
 * must let this through instead: a nickname would be replaced by a random one, an identity by a
 * new one, and a saved list by one built from nothing.
 *
 * No kind of it is told from another. Which of these a failure is cannot be known from where it
 * shows: a locked Keychain has been seen to refuse, and nothing rules out that it answers "not
 * found" or anything else. So whoever has to decide what to do about one decides the same for
 * all: nothing is created or replaced, and what needed the store is tried again later.
 */
class SecureStoreUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * Whether this failure, or anything it was caused by, is the secure store not answering. A start
 * that fails this way is to be tried again; whatever wrapped it on its way up (a refused
 * identity, a dependency that could not be built) does not change that.
 */
fun Throwable.isSecureStoreUnavailable(): Boolean {
    var failure: Throwable? = this
    // A cause chain is short; the bound only keeps a chain that loops from never ending.
    repeat(32) {
        val current = failure ?: return false
        if (current is SecureStoreUnavailableException) return true
        failure = current.cause
    }
    return false
}
