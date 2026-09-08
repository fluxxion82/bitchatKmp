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
 *
 * ## Its role now
 *
 * Nothing calls this in production any more. Every mint goes through the single custodian, whose
 * decision table is `com.bitchat.local.identity.IdentityMintGate`.
 *
 * It is kept, deliberately, as the **frozen reference definition** of the decision this code
 * made on platforms that have no directory domain and no identity ledger - Android, Apple and
 * JVM desktop, which is to say everything but the embedded Linux build.
 * `IdentityMintGateTest.platforms with no domain and no ledger reproduce the pre-custodian
 * decision exactly` asserts the gate against *this class*, not against a copy of its table, so a
 * drift on either side fails a test rather than quietly changing behaviour on three platforms.
 *
 * It cannot become a delegation to that gate, which is what the plan asked for: the gate lives
 * in `:data:local:platform`, and this module cannot depend on it without a cycle
 * (`:data:local:platform` already depends on `:data:remote:transport:nostr`). Being the oracle
 * rather than a forwarder is also the stronger arrangement - a forwarder asserts nothing.
 *
 * Do not change the strings below without deciding, deliberately, that the three
 * keychain-backed platforms should behave differently than they did.
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
