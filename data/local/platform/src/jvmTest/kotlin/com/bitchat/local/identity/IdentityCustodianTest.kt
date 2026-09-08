package com.bitchat.local.identity

import com.bitchat.local.prefs.PreferenceStoreState
import com.bitchat.transport.IdentityRefusedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val EPOCH = "0123456789abcdef0123456789abcdef"

/** The identity store, as the custodian sees it, plus enough of one to write through. */
private class FakeStore(
    initial: Map<String, String> = emptyMap(),
    var damaged: Boolean = false,
) : IdentityRecordStore {
    val records = LinkedHashMap(initial)

    override fun record(key: String): String? = records[key]

    override fun state(): PreferenceStoreState =
        PreferenceStoreState.of(
            damage = if (damaged) listOf("truncated") else emptyList(),
            isEmpty = records.isEmpty(),
        )
}

private class FakeInspector(private val verdicts: MutableList<DomainVerdict>) : DomainInspector {
    var calls = 0
    constructor(vararg verdicts: DomainVerdict) : this(verdicts.toMutableList())

    override fun scan(): DomainVerdict {
        calls++
        return if (verdicts.size == 1) verdicts.single() else verdicts.removeAt(0)
    }
}

private class FakeLedger(
    var claims: LedgerClaims = LedgerClaims.Absent,
    private val writable: Boolean = true,
) : LedgerStore {
    var writes = 0
    override val location: String = "/tmp/identity-ledger"
    override fun read(): LedgerClaims = claims
    override fun write(claims: LedgerClaims.Present): Boolean {
        if (!writable) return false
        writes++
        this.claims = claims
        return true
    }
}

private fun custodian(
    store: FakeStore,
    inspector: DomainInspector,
    ledger: LedgerStore,
) = IdentityCustodian(
    store = store,
    inspector = inspector,
    ledgerStore = ledger,
    newEpoch = { EPOCH },
    log = { },
)

/** Mints [component] the way the production call sites do: through the store's own record. */
private fun IdentityCustodian.mintString(
    store: FakeStore,
    component: IdentityComponent,
    value: String,
    claim: String = "pub-$value",
): String = loadOrMint(
    component = component,
    load = { store.records[component.storeKey] },
    claimOf = { claim },
    persist = { store.records[component.storeKey] = it },
    mint = { value },
)

class IdentityCustodianTest {

    @Test
    fun `a brand new device mints and records the claim after the key`() {
        val store = FakeStore()
        val ledger = FakeLedger()
        val custodian = custodian(store, FakeInspector(DomainVerdict.Virgin), ledger)

        assertEquals("k1", custodian.mintString(store, IdentityComponent.NOSTR_PRIVATE, "k1"))

        assertEquals("k1", store.records["nostr_private_key"])
        val claims = assertIs<LedgerClaims.Present>(ledger.claims)
        assertEquals("pub-k1", IdentityLedger.claim(claims, IdentityComponent.NOSTR_PRIVATE))
    }

    @Test
    fun `the genuine first-run ordering still works end to end`() {
        // The one that must not regress. On a real new device the Bluetooth module writes the
        // mesh signing key into this store while Koin builds the graph, long before Nostr is
        // asked for anything, so Nostr always arrives at a store that already holds keys and a
        // domain that the application itself has already made non-virgin. Both components must
        // still be created.
        val store = FakeStore()
        val ledger = FakeLedger()
        val inspector = FakeInspector(
            DomainVerdict.Virgin,
            // Anything scanned after the first write sees the application's own footprints.
            DomainVerdict.Inhabited(
                listOf(DomainArtifact("/prefs", "bitchat_identity.prefs", ArtifactClass.PLAINTEXT_STORE)),
            ),
        )
        val custodian = custodian(store, inspector, ledger)

        // 1. bluetoothModule, during Koin graph construction.
        custodian.mintString(store, IdentityComponent.MESH_SIGNING, "signing", claim = "aa11")
        // 2. NostrClient, later, against a store that is now POPULATED.
        custodian.mintString(store, IdentityComponent.NOSTR_PRIVATE, "nostr", claim = "beef")
        // 3. The device seed, later still.
        custodian.mintString(
            store,
            IdentityComponent.NOSTR_DEVICE_SEED,
            "seed",
            claim = IdentityLedger.CLAIM_PRESENT,
        )

        assertEquals(PreferenceStoreState.POPULATED, store.state())
        val claims = assertIs<LedgerClaims.Present>(ledger.claims)
        assertEquals("aa11", IdentityLedger.claim(claims, IdentityComponent.MESH_SIGNING))
        assertEquals("beef", IdentityLedger.claim(claims, IdentityComponent.NOSTR_PRIVATE))
        assertEquals(
            IdentityLedger.CLAIM_PRESENT,
            IdentityLedger.claim(claims, IdentityComponent.NOSTR_DEVICE_SEED),
        )
    }

    @Test
    fun `the domain reading is taken once and cached for the process`() {
        val store = FakeStore()
        val inspector = FakeInspector(
            DomainVerdict.Virgin,
            DomainVerdict.Inhabited(listOf(DomainArtifact("/prefs", "x.prefs", ArtifactClass.PLAINTEXT_STORE))),
        )
        val custodian = custodian(store, inspector, FakeLedger())

        custodian.mintString(store, IdentityComponent.MESH_SIGNING, "a")
        custodian.mintString(store, IdentityComponent.NOSTR_PRIVATE, "b")

        assertEquals(1, inspector.calls)
    }

    @Test
    fun `a claim with the component missing refuses instead of minting`() {
        // The truncation case, end to end: the store parses cleanly and still holds the mesh
        // keys, but the Nostr records are gone. Today's policy reads that as "populated but
        // lacking my key" and mints.
        val store = FakeStore(mapOf("signing_private_key" to "signing"))
        val ledger = FakeLedger(
            IdentityLedger.withClaim(
                IdentityLedger.withClaim(IdentityLedger.create(EPOCH), IdentityComponent.MESH_SIGNING, "aa11"),
                IdentityComponent.NOSTR_PRIVATE,
                "beef",
            ),
        )
        val custodian = custodian(store, FakeInspector(DomainVerdict.Inhabited(emptyList())), ledger)

        val refusal = assertFailsWith<IdentityRefusedException> {
            custodian.mintString(store, IdentityComponent.NOSTR_PRIVATE, "replacement")
        }

        assertTrue(refusal.reason.contains("recovered, not replaced"), refusal.reason)
        assertTrue(store.records["nostr_private_key"] == null, "nothing may have been written")
    }

    @Test
    fun `an indeterminate domain refuses`() {
        val store = FakeStore()
        val custodian = custodian(
            store,
            FakeInspector(DomainVerdict.Indeterminate(listOf("/prefs: Permission denied"))),
            FakeLedger(),
        )

        assertFailsWith<IdentityRefusedException> {
            custodian.mintString(store, IdentityComponent.NOSTR_PRIVATE, "k")
        }
    }

    @Test
    fun `a clean populated store loads without minting and records the missing claim`() {
        // The steady state on the device that already exists: nothing is created, and the
        // ledger acquires its claims simply by the device being started. That is what lets an
        // existing device gain the protection with no migration step.
        val store = FakeStore(
            mapOf("nostr_private_key" to "live", "signing_private_key" to "signing"),
        )
        val ledger = FakeLedger()
        val custodian = custodian(store, FakeInspector(DomainVerdict.Inhabited(emptyList())), ledger)

        assertEquals(
            "live",
            custodian.mintString(store, IdentityComponent.NOSTR_PRIVATE, "must-not-be-used"),
        )

        val claims = assertIs<LedgerClaims.Present>(ledger.claims)
        assertEquals("pub-must-not-be-used", IdentityLedger.claim(claims, IdentityComponent.NOSTR_PRIVATE))
    }

    @Test
    fun `recording a claim on load is idempotent`() {
        val store = FakeStore(mapOf("nostr_private_key" to "live"))
        val ledger = FakeLedger()
        val custodian = custodian(store, FakeInspector(DomainVerdict.Inhabited(emptyList())), ledger)

        repeat(3) { custodian.mintString(store, IdentityComponent.NOSTR_PRIVATE, "unused") }

        assertEquals(1, ledger.writes)
    }

    @Test
    fun `a record that is present but unusable is never replaced`() {
        // loadSigningKey() returns null when the public half is missing or the wrong size, but
        // the private half is still on the disk. Minting there would overwrite key material.
        val store = FakeStore(mapOf("signing_private_key" to "half-a-keypair"))
        val custodian = custodian(store, FakeInspector(DomainVerdict.Virgin), FakeLedger())

        val refusal = assertFailsWith<IdentityRefusedException> {
            custodian.loadOrMint(
                component = IdentityComponent.MESH_SIGNING,
                load = { null },
                claimOf = { "x" },
                persist = { store.records["signing_private_key"] = "replacement" },
                mint = { "replacement" },
            )
        }

        assertTrue(refusal.reason.contains("could not be used"), refusal.reason)
        assertEquals("half-a-keypair", store.records["signing_private_key"])
    }

    @Test
    fun `a ledger that cannot be written does not fail the mint`() {
        // Key first, claim second: a claim that could not be recorded is found and recorded on
        // the next start. Failing here would leave a device with no identity at all.
        val store = FakeStore()
        val ledger = FakeLedger(writable = false)
        val custodian = custodian(store, FakeInspector(DomainVerdict.Virgin), ledger)

        assertEquals("k", custodian.mintString(store, IdentityComponent.NOSTR_PRIVATE, "k"))
        assertEquals("k", store.records["nostr_private_key"])
    }

    @Test
    fun `a public form that cannot be derived does not fail the load`() {
        val store = FakeStore(mapOf("nostr_private_key" to "garbage"))
        val ledger = FakeLedger()
        val custodian = custodian(store, FakeInspector(DomainVerdict.Inhabited(emptyList())), ledger)

        val value = custodian.loadOrMint(
            component = IdentityComponent.NOSTR_PRIVATE,
            load = { store.records["nostr_private_key"] },
            claimOf = { error("not a valid key") },
            persist = { },
            mint = { error("must not mint") },
        )

        assertEquals("garbage", value)
        assertEquals(LedgerClaims.Absent, ledger.claims)
    }

    @Test
    fun `a ledger that disagrees with the store is reported, not rewritten`() {
        val store = FakeStore(mapOf("nostr_private_key" to "live"))
        val ledger = FakeLedger(
            IdentityLedger.withClaim(
                IdentityLedger.create(EPOCH),
                IdentityComponent.NOSTR_PRIVATE,
                "a-different-device",
            ),
        )
        val custodian = custodian(store, FakeInspector(DomainVerdict.Inhabited(emptyList())), ledger)

        custodian.mintString(store, IdentityComponent.NOSTR_PRIVATE, "unused", claim = "beef")

        assertEquals(0, ledger.writes)
        val claims = assertIs<LedgerClaims.Present>(ledger.claims)
        assertEquals("a-different-device", IdentityLedger.claim(claims, IdentityComponent.NOSTR_PRIVATE))
    }

    @Test
    fun `platforms with no domain and no ledger behave exactly as they do today`() {
        // Android, Apple and JVM desktop, which register NoDomainInspector and NoLedgerStore.
        // FIRST_RUN and POPULATED mint; UNREADABLE refuses; nothing is ever written to a ledger.
        val firstRun = FakeStore()
        assertEquals(
            "k",
            custodian(firstRun, NoDomainInspector, NoLedgerStore)
                .mintString(firstRun, IdentityComponent.NOSTR_PRIVATE, "k"),
        )

        val populated = FakeStore(mapOf("signing_private_key" to "signing"))
        assertEquals(
            "k",
            custodian(populated, NoDomainInspector, NoLedgerStore)
                .mintString(populated, IdentityComponent.NOSTR_PRIVATE, "k"),
        )

        val unreadable = FakeStore(mapOf("signing_private_key" to "signing"), damaged = true)
        assertFailsWith<IdentityRefusedException> {
            custodian(unreadable, NoDomainInspector, NoLedgerStore)
                .mintString(unreadable, IdentityComponent.NOSTR_PRIVATE, "k")
        }
    }

    @Test
    fun `an inhabited domain with no ledger refuses, and adoption then lets it through`() {
        val store = FakeStore(mapOf("signing_private_key" to "signing"))
        val ledger = FakeLedger()
        val inhabited = DomainVerdict.Inhabited(
            listOf(DomainArtifact("/prefs", "bitchat_identity.prefs", ArtifactClass.PLAINTEXT_STORE)),
        )

        assertFailsWith<IdentityRefusedException> {
            custodian(store, FakeInspector(inhabited), ledger)
                .mintString(store, IdentityComponent.NOSTR_PRIVATE, "k")
        }

        // What --identity-adopt will write, or what a successful load of any component records.
        ledger.claims = IdentityLedger.withClaim(
            IdentityLedger.create(EPOCH),
            IdentityComponent.MESH_SIGNING,
            "aa11",
        )

        assertEquals(
            "k",
            custodian(store, FakeInspector(inhabited), ledger)
                .mintString(store, IdentityComponent.NOSTR_PRIVATE, "k"),
        )
    }
}
