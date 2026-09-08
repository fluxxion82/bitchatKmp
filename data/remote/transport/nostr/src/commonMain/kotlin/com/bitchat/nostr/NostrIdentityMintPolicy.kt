package com.bitchat.nostr

import com.bitchat.transport.IdentityStoreState

/** What to do when a key that the Nostr identity depends on is not in the store. */
sealed interface MintDecision {

    /** Create it. */
    sealed interface Allowed : MintDecision {
        /** Nothing to say: nothing was ever stored here. */
        data object FirstRun : Allowed

        /** Create it, but say why this was not obviously a first run. */
        data class Noteworthy(val reason: String) : Allowed
    }

    /** Do not create it: a replacement would be written over an identity that may still exist. */
    data class Refuse(val reason: String) : MintDecision
}

/**
 * Decides whether a missing Nostr key may be replaced with a fresh one.
 *
 * Minting is the right answer exactly once in a device's life, and the wrong answer every other
 * time: a new Nostr identity means a new npub, so every peer that had favourited this device
 * loses it, and every message addressed to the old key is undeliverable. The old code could not
 * tell the two apart, because a store that failed to load looks exactly like a store that was
 * never written.
 */
object NostrIdentityMintPolicy {

    fun decide(state: IdentityStoreState, key: String): MintDecision = when (state) {
        IdentityStoreState.FIRST_RUN ->
            MintDecision.Allowed.FirstRun

        // Not refused: on a genuine first run the mesh signing key is written to this same
        // store before Nostr ever asks for its key (bluetoothModule creates CryptoSigningFacade
        // during Koin graph construction), so "other keys but not this one" is a state every
        // new device passes through. It is still worth a line in the log, because it is also
        // what a partially lost store looks like.
        IdentityStoreState.POPULATED ->
            MintDecision.Allowed.Noteworthy(
                "identity store holds other keys but no '$key'; creating one"
            )

        IdentityStoreState.UNREADABLE ->
            MintDecision.Refuse(
                "identity store did not load cleanly, so '$key' may exist on disk; " +
                    "refusing to create a replacement that would overwrite it"
            )
    }
}
