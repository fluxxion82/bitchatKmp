package com.bitchat.local.identity

/**
 * Looks at the directories this application writes to and reports whether they prove a first run.
 *
 * Implementations must take their reading **once, as early as possible**, and return the same
 * verdict for the lifetime of the process. The application's own first write makes the domain
 * inhabited, so a reading taken later than the first store construction would refuse the second
 * component of a genuine first run - a brand new device permanently unable to create an
 * identity, which is the failure mode in the other direction and just as bad.
 */
interface DomainInspector {
    fun scan(): DomainVerdict
}

/**
 * For platforms with no directory domain: Android, Apple and JVM desktop.
 *
 * Their stores are the Keychain and EncryptedSharedPreferences, which raise a read failure as an
 * exception rather than handing back a half-loaded map, so there is nothing to scan and nothing
 * a scan could add. Paired with [NoLedgerStore] this reproduces the pre-custodian behaviour
 * exactly - see [IdentityMintGate].
 */
object NoDomainInspector : DomainInspector {
    override fun scan(): DomainVerdict = DomainVerdict.NotApplicable
}
