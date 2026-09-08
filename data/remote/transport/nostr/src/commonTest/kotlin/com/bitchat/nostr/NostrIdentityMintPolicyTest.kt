package com.bitchat.nostr

import com.bitchat.transport.IdentityStoreState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NostrIdentityMintPolicyTest {

    @Test
    fun `a first run mints without comment`() {
        assertEquals(
            MintDecision.Allowed.FirstRun,
            NostrIdentityMintPolicy.decide(IdentityStoreState.FIRST_RUN, "nostr_private_key"),
        )
    }

    @Test
    fun `an unreadable store refuses to mint`() {
        // The whole point: a store that failed to load looks identical to a store that was
        // never written, and minting there overwrites a live identity with a new npub.
        val decision = NostrIdentityMintPolicy.decide(
            IdentityStoreState.UNREADABLE,
            "nostr_private_key",
        )

        val refusal = assertIs<MintDecision.Refuse>(decision)
        assertTrue(refusal.reason.contains("nostr_private_key"), refusal.reason)
        assertTrue(refusal.reason.contains("did not load cleanly"), refusal.reason)
    }

    @Test
    fun `a populated store still mints, because a new device passes through that state`() {
        // bluetoothModule writes the mesh signing key into this same store while Koin builds
        // the graph, so on a genuine first run Nostr always finds "other keys but not mine".
        // Refusing here would leave every new device without a Nostr identity for ever.
        val decision = NostrIdentityMintPolicy.decide(
            IdentityStoreState.POPULATED,
            "nostr_private_key",
        )

        val allowed = assertIs<MintDecision.Allowed.Noteworthy>(decision)
        assertTrue(allowed.reason.contains("nostr_private_key"), allowed.reason)
    }

    @Test
    fun `minting is never silent outside a genuine first run`() {
        // FIRST_RUN is the only state that may pass without leaving a trace in the log.
        for (state in IdentityStoreState.entries) {
            val decision = NostrIdentityMintPolicy.decide(state, "nostr_device_seed")
            if (state != IdentityStoreState.FIRST_RUN) {
                assertTrue(
                    decision !is MintDecision.Allowed.FirstRun,
                    "$state must not mint silently",
                )
            }
        }
    }
}
