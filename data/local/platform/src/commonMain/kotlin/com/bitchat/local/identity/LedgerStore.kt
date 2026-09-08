package com.bitchat.local.identity

/**
 * Persistence for the [IdentityLedger].
 *
 * Deliberately failure-tolerant in one direction only. [read] never throws, because a ledger
 * that cannot be read must present as [LedgerClaims.Damaged] (which refuses) rather than as an
 * exception somewhere up the startup path. [write] never throws either, because a claim that
 * could not be recorded must not fail the load that produced it: the key is already saved, and
 * the next start records the claim on the way past.
 */
interface LedgerStore {

    /** Where the ledger lives, for error messages. */
    val location: String

    /** Reads the ledger. A read failure is [LedgerClaims.Damaged], never [LedgerClaims.Absent]. */
    fun read(): LedgerClaims

    /** Persists [claims] durably. Returns false when it could not be written. */
    fun write(claims: LedgerClaims.Present): Boolean
}

/** For platforms that keep no ledger: Android, Apple and JVM desktop. See [NoDomainInspector]. */
object NoLedgerStore : LedgerStore {
    override val location: String = "(no identity ledger on this platform)"
    override fun read(): LedgerClaims = LedgerClaims.Unavailable
    override fun write(claims: LedgerClaims.Present): Boolean = false
}
