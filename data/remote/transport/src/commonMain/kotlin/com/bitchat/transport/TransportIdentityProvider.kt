package com.bitchat.transport

/**
 * How the identity store presented itself when it was opened.
 *
 * [TransportIdentityProvider.loadKey] returns null both when a key was never written and when
 * the store that holds it could not be read. Those need very different answers - the first is a
 * first run, the second must never be answered by minting a replacement over a live identity -
 * so the reason is reported separately.
 */
enum class IdentityStoreState {
    /** Nothing is stored. A genuine first run (or a deliberate wipe): minting is correct. */
    FIRST_RUN,

    /** The store loaded cleanly and holds other keys. A missing key is a real gap. */
    POPULATED,

    /** The store did not load cleanly. A missing key may simply have been lost. */
    UNREADABLE,
}

/**
 * Thrown instead of creating identity material that may already exist somewhere on this device.
 *
 * It lives in this module rather than beside the guard that raises it because this module is the
 * one leaf that both the identity store and the transports can see; the guard itself has to live
 * next to the preference store it inspects.
 *
 * @param reason what was seen, in the operator's terms.
 * @param remedy a command, or the shape of one, that a human can act on. A refusal that does not
 *   say what to do next is a black screen on a headless box.
 */
class IdentityRefusedException(
    val reason: String,
    val remedy: String,
) : RuntimeException("$reason\n  remedy: $remedy")

interface TransportIdentityProvider {
    fun loadKey(key: String): String?
    fun saveKey(key: String, value: String)
    fun hasKey(key: String): Boolean
    fun removeKeys(vararg keys: String)

    fun clearAll()

    /**
     * Returns the stored value for [key], creating one only when the device can prove it has
     * never created it before.
     *
     * This is the only way a transport may bring identity material into existence. [loadKey]
     * plus "it was null, so make one" is the bug this replaces: a store that failed to load, or
     * one truncated at a record boundary, is indistinguishable from a store that was never
     * written, and minting there abandons an identity that is still on the disk.
     *
     * @param publicFormOf maps a stored value to the public value recorded against it - an
     *   x-only public key, say. Used for the ledger claim on both the load and the create path,
     *   and never allowed to fail either of them.
     * @param mint creates the value. Called only after the guard has allowed it.
     * @throws IdentityRefusedException when creating [key] could overwrite a recoverable one.
     */
    fun loadOrMint(key: String, publicFormOf: (String) -> String, mint: () -> String): String

    /**
     * Why a key might be missing. Consult this before creating one: see [IdentityStoreState].
     */
    fun storeState(): IdentityStoreState
}
