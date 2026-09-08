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

interface TransportIdentityProvider {
    fun loadKey(key: String): String?
    fun saveKey(key: String, value: String)
    fun hasKey(key: String): Boolean
    fun removeKeys(vararg keys: String)

    fun clearAll()

    /**
     * Why a key might be missing. Consult this before creating one: see [IdentityStoreState].
     */
    fun storeState(): IdentityStoreState
}
