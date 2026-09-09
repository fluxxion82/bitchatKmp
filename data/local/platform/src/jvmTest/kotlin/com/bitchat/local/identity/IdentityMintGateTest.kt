package com.bitchat.local.identity

import com.bitchat.local.prefs.PreferenceStoreState
import com.bitchat.nostr.MintDecision
import com.bitchat.nostr.NostrIdentityMintPolicy
import com.bitchat.transport.IdentityStoreState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val EPOCH = "0123456789abcdef0123456789abcdef"

private val INHABITED = DomainVerdict.Inhabited(
    listOf(
        DomainArtifact(
            "/home/pi/.bitchat/prefs",
            "bitchat_identity.prefs",
            ArtifactClass.PLAINTEXT_STORE,
        ),
    ),
)

private fun ledgerWith(vararg claims: Pair<IdentityComponent, String>): LedgerClaims.Present =
    claims.fold(IdentityLedger.create(EPOCH)) { acc, (component, value) ->
        IdentityLedger.withClaim(acc, component, value)
    }

class IdentityMintGateTest {

    @Test
    fun `a virgin domain with no ledger and an empty store mints`() {
        assertEquals(
            MintVerdict.AllowedFirstRun,
            IdentityMintGate.decide(
                component = IdentityComponent.MESH_SIGNING,
                domain = DomainVerdict.Virgin,
                store = PreferenceStoreState.FIRST_RUN,
                ledger = LedgerClaims.Absent,
            ),
        )
    }

    @Test
    fun `an inhabited domain with no ledger refuses and names the adoption command`() {
        val verdict = assertIs<MintVerdict.Refuse>(
            IdentityMintGate.decide(
                component = IdentityComponent.NOSTR_PRIVATE,
                domain = INHABITED,
                store = PreferenceStoreState.POPULATED,
                ledger = LedgerClaims.Absent,
            ),
        )

        assertTrue(verdict.reason.contains("nostr_private_key"), verdict.reason)
        assertTrue(verdict.remedy.contains("--identity-adopt"), verdict.remedy)
    }

    @Test
    fun `a damaged ledger refuses whatever the store says`() {
        for (state in PreferenceStoreState.entries) {
            val verdict = IdentityMintGate.decide(
                component = IdentityComponent.NOSTR_PRIVATE,
                domain = DomainVerdict.Virgin,
                store = state,
                ledger = LedgerClaims.Damaged(listOf("no 'epoch' record")),
            )

            assertIs<MintVerdict.Refuse>(verdict, "store state $state must not mint")
        }
    }

    @Test
    fun `a claim with the key missing from the store refuses and says recover, not replace`() {
        // THE case. A store truncated exactly at a record boundary parses cleanly and reports
        // POPULATED, which is byte-for-byte what a genuine first run looks like once the mesh
        // signing key has been written. The claim recorded outside that file is the only thing
        // that can tell them apart.
        val verdict = assertIs<MintVerdict.Refuse>(
            IdentityMintGate.decide(
                component = IdentityComponent.NOSTR_PRIVATE,
                domain = INHABITED,
                store = PreferenceStoreState.POPULATED,
                ledger = ledgerWith(
                    IdentityComponent.MESH_SIGNING to "aa11",
                    IdentityComponent.NOSTR_PRIVATE to "beef",
                ),
            ),
        )

        assertTrue(verdict.reason.contains("NOSTR_PRIVATE"), verdict.reason)
        assertTrue(verdict.reason.contains("claim.nostr=beef"), verdict.reason)
        assertTrue(verdict.reason.contains("recovered, not replaced"), verdict.reason)
        assertTrue(verdict.remedy.contains("restore"), verdict.remedy)
    }

    @Test
    fun `a clean ledger with no claim mints even when the store holds other keys`() {
        // The genuine first-run ordering: the Bluetooth module has already written the mesh
        // signing key into this store, so Nostr always finds "other keys but not mine".
        // Refusing here would leave every new device without a Nostr identity for ever.
        val verdict = assertIs<MintVerdict.AllowedNoteworthy>(
            IdentityMintGate.decide(
                component = IdentityComponent.NOSTR_PRIVATE,
                domain = INHABITED,
                store = PreferenceStoreState.POPULATED,
                ledger = ledgerWith(IdentityComponent.MESH_SIGNING to "aa11"),
            ),
        )

        assertTrue(verdict.reason.contains("nostr_private_key"), verdict.reason)
    }

    @Test
    fun `an unreadable store refuses even with a clean ledger and no claim`() {
        val verdict = assertIs<MintVerdict.Refuse>(
            IdentityMintGate.decide(
                component = IdentityComponent.NOSTR_PRIVATE,
                domain = INHABITED,
                store = PreferenceStoreState.UNREADABLE,
                ledger = ledgerWith(IdentityComponent.MESH_SIGNING to "aa11"),
            ),
        )

        assertTrue(verdict.reason.contains("did not load cleanly"), verdict.reason)
    }

    @Test
    fun `an indeterminate domain refuses even when the store says first run`() {
        val verdict = assertIs<MintVerdict.Refuse>(
            IdentityMintGate.decide(
                component = IdentityComponent.MESH_SIGNING,
                domain = DomainVerdict.Indeterminate(
                    listOf("/home/pi/.config/bitchat: Permission denied"),
                ),
                store = PreferenceStoreState.FIRST_RUN,
                ledger = LedgerClaims.Absent,
            ),
        )

        assertTrue(verdict.reason.contains("could not be read"), verdict.reason)
    }

    @Test
    fun `a half-configured guard refuses rather than guessing`() {
        for (ledger in listOf(LedgerClaims.Absent, ledgerWith())) {
            assertIs<MintVerdict.Refuse>(
                IdentityMintGate.decide(
                    component = IdentityComponent.NOSTR_PRIVATE,
                    domain = DomainVerdict.NotApplicable,
                    store = PreferenceStoreState.FIRST_RUN,
                    ledger = ledger,
                ),
            )
        }

        assertIs<MintVerdict.Refuse>(
            IdentityMintGate.decide(
                component = IdentityComponent.NOSTR_PRIVATE,
                domain = DomainVerdict.Virgin,
                store = PreferenceStoreState.FIRST_RUN,
                ledger = LedgerClaims.Unavailable,
            ),
        )
    }

    @Test
    fun `platforms with no domain and no ledger reproduce the pre-custodian decision exactly`() {
        // Android, Apple and JVM desktop. Asserted against the class that made the decision
        // before this work existed, not against a copy of its table, so a change to either side
        // fails here.
        val states = listOf(
            PreferenceStoreState.FIRST_RUN to IdentityStoreState.FIRST_RUN,
            PreferenceStoreState.POPULATED to IdentityStoreState.POPULATED,
            PreferenceStoreState.UNREADABLE to IdentityStoreState.UNREADABLE,
        )

        for (component in IdentityComponent.entries) {
            for ((preferenceState, transportState) in states) {
                val gate = IdentityMintGate.decide(
                    component = component,
                    domain = DomainVerdict.NotApplicable,
                    store = preferenceState,
                    ledger = LedgerClaims.Unavailable,
                )
                val legacy = NostrIdentityMintPolicy.decide(transportState, component.storeKey)

                val message = "$component / $preferenceState: gate=$gate legacy=$legacy"
                when (legacy) {
                    MintDecision.Allowed.FirstRun ->
                        assertEquals(MintVerdict.AllowedFirstRun, gate, message)

                    is MintDecision.Allowed.Noteworthy ->
                        assertEquals(
                            MintVerdict.AllowedNoteworthy(legacy.reason),
                            gate,
                            message,
                        )

                    is MintDecision.Refuse ->
                        assertEquals(legacy.reason, assertIs<MintVerdict.Refuse>(gate, message).reason, message)
                }
            }
        }
    }
}
