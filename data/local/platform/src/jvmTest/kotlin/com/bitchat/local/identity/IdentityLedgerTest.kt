package com.bitchat.local.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val EPOCH = "0123456789abcdef0123456789abcdef"

class IdentityLedgerTest {

    @Test
    fun `no file at all is absent, not damaged and not empty`() {
        assertEquals(LedgerClaims.Absent, IdentityLedger.parse(null))
    }

    @Test
    fun `claims round-trip through encode and parse`() {
        val written = IdentityLedger.withClaim(
            IdentityLedger.withClaim(IdentityLedger.create(EPOCH), IdentityComponent.MESH_SIGNING, "aa11"),
            IdentityComponent.NOSTR_DEVICE_SEED,
            IdentityLedger.CLAIM_PRESENT,
        )

        val read = assertIs<LedgerClaims.Present>(IdentityLedger.parse(IdentityLedger.encode(written)))

        assertEquals(EPOCH, read.epoch)
        assertEquals("aa11", IdentityLedger.claim(read, IdentityComponent.MESH_SIGNING))
        assertEquals(
            IdentityLedger.CLAIM_PRESENT,
            IdentityLedger.claim(read, IdentityComponent.NOSTR_DEVICE_SEED),
        )
        assertNull(IdentityLedger.claim(read, IdentityComponent.NOSTR_PRIVATE))
    }

    @Test
    fun `a damaged file is damaged, never present with no claims`() {
        // The truncation the whole design is about: the file does not end in a newline, so the
        // decoder reports damage. Reading that as "this device has claimed nothing" would let
        // the gate mint over a live identity.
        val truncated = "version=1\nepoch=$EPOCH\nclaim.nostr=abcd"

        val parsed = assertIs<LedgerClaims.Damaged>(IdentityLedger.parse(truncated))
        assertTrue(parsed.reasons.any { it.contains("truncated") }, parsed.reasons.toString())
    }

    @Test
    fun `a missing version is damaged`() {
        assertIs<LedgerClaims.Damaged>(IdentityLedger.parse("epoch=$EPOCH\nclaim.nostr=abcd\n"))
    }

    @Test
    fun `an unknown version is damaged`() {
        val parsed = assertIs<LedgerClaims.Damaged>(
            IdentityLedger.parse("version=2\nepoch=$EPOCH\n"),
        )
        assertTrue(parsed.reasons.any { it.contains("unknown version") }, parsed.reasons.toString())
    }

    @Test
    fun `a missing epoch is damaged`() {
        assertIs<LedgerClaims.Damaged>(IdentityLedger.parse("version=1\nclaim.nostr=abcd\n"))
    }

    @Test
    fun `an empty file is damaged, not absent`() {
        assertIs<LedgerClaims.Damaged>(IdentityLedger.parse(""))
    }

    @Test
    fun `an unknown claim survives a round trip and does not make the ledger damaged`() {
        // A ledger written by a later build must not make this build declare it damaged, and
        // must not lose the claim it did not understand.
        val text = "version=1\nepoch=$EPOCH\nclaim.future_component=deadbeef\n"

        val parsed = assertIs<LedgerClaims.Present>(IdentityLedger.parse(text))
        assertEquals("deadbeef", parsed.claims["claim.future_component"])
        assertEquals(text, IdentityLedger.encode(parsed))
    }

    @Test
    fun `withClaim is a no-op for the same value`() {
        val once = IdentityLedger.withClaim(
            IdentityLedger.create(EPOCH),
            IdentityComponent.NOSTR_PRIVATE,
            "abcd",
        )

        // Same instance: recording a claim on every successful load must cost no write.
        assertSame(once, IdentityLedger.withClaim(once, IdentityComponent.NOSTR_PRIVATE, "abcd"))
    }

    @Test
    fun `withClaim refuses to overwrite a different value`() {
        val once = IdentityLedger.withClaim(
            IdentityLedger.create(EPOCH),
            IdentityComponent.NOSTR_PRIVATE,
            "abcd",
        )

        assertFailsWith<IllegalStateException> {
            IdentityLedger.withClaim(once, IdentityComponent.NOSTR_PRIVATE, "0000")
        }
    }

    @Test
    fun `withClaim refuses an empty value`() {
        assertFailsWith<IllegalArgumentException> {
            IdentityLedger.withClaim(IdentityLedger.create(EPOCH), IdentityComponent.NOSTR_PRIVATE, "")
        }
    }

    @Test
    fun `every component has a distinct store key and claim name`() {
        val components = IdentityComponent.entries
        assertEquals(components.size, components.map { it.storeKey }.toSet().size)
        assertEquals(components.size, components.map { it.claimName }.toSet().size)
        components.forEach {
            assertEquals(it, IdentityComponent.forStoreKey(it.storeKey))
            assertTrue(it.claimName.startsWith("claim."), it.claimName)
        }
        assertNull(IdentityComponent.forStoreKey("nickname"))
    }
}
