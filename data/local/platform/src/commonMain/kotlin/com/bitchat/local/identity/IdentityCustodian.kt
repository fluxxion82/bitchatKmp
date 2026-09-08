package com.bitchat.local.identity

import com.bitchat.local.prefs.PreferenceStoreState
import com.bitchat.transport.IdentityRefusedException
import kotlin.random.Random

/**
 * The narrow view of the identity store that the custodian needs: is the record there, and did
 * the store that would hold it load cleanly.
 */
interface IdentityRecordStore {
    /** The raw record under [key], or null when there is no such record. */
    fun record(key: String): String?

    /** How the backing store presented itself when it was opened. */
    fun state(): PreferenceStoreState
}

/**
 * The only code in this tree that may bring identity key material into existence.
 *
 * Every mint site routes through here: the two Nostr keys, via
 * `TransportIdentityProvider.loadOrMint`, and the mesh signing key, via
 * `SecureIdentityPreferences.loadOrMintSigningKey`. The property is checkable by grep - no other
 * caller of `NostrIdentity.generate()`, `Cryptography.generateEd25519KeyPair()` or a random fill
 * into an identity slot.
 *
 * ## Two orderings that are easy to get wrong, and both matter
 *
 * **The domain scan is taken once and cached for the process lifetime.** The application's own
 * first write makes the domain inhabited, so re-scanning per call would refuse the second
 * component of a genuine first run. [DomainInspector] implementations take their reading at
 * construction and the linux one is created eagerly, before any store exists; this cache is the
 * second line of the same defence.
 *
 * **A claim is recorded after the key is saved, never before,** and also after a successful
 * *load* of a component that has no claim yet, so devices that predate the ledger acquire their
 * claims by being started rather than by being migrated. Claim first then a failed save gives a
 * claim with no key, which is a permanent refusal to mint on a device that has no identity - a
 * brick. Key first then a failed claim gives a key with no claim, which the next start finds,
 * does not mint over, and records on the way past.
 *
 * Recording a claim can never fail an operation: not when the ledger cannot be written, not when
 * the public form cannot be derived, and not when the recorded claim disagrees with what is on
 * disk. Each of those is logged loudly and left alone, because the alternative - failing a load
 * that has already succeeded - loses more than it protects.
 */
class IdentityCustodian(
    private val store: IdentityRecordStore,
    private val inspector: DomainInspector,
    private val ledgerStore: LedgerStore,
    private val newEpoch: () -> String = ::randomEpoch,
    private val log: (String) -> Unit = ::println,
) {
    private var cachedDomain: DomainVerdict? = null
    private var cachedLedger: LedgerClaims? = null

    /** What the identity domain looked like the first time anyone asked, for the whole process. */
    fun verdict(): DomainVerdict = cachedDomain ?: inspector.scan().also { cachedDomain = it }

    /** The raw stored value for [component], without any guard: reading creates nothing. */
    fun load(component: IdentityComponent): String? = store.record(component.storeKey)

    /**
     * Returns the existing value for [component], or creates one when - and only when - the
     * invariant is satisfied.
     *
     * @param load reads the component in whatever shape its owner uses. Returning null means
     *   "not usable", which is not the same as "not there": if the record *is* on disk this
     *   refuses rather than replacing it.
     * @param claimOf the public value to record against the component in the ledger.
     * @param persist saves a freshly created value. Runs before the claim is recorded.
     * @param mint creates a value. Called only after the gate has allowed it.
     * @throws IdentityRefusedException when creating it could abandon a recoverable identity.
     */
    fun <T : Any> loadOrMint(
        component: IdentityComponent,
        load: () -> T?,
        claimOf: (T) -> String,
        persist: (T) -> Unit,
        mint: () -> T,
    ): T {
        load()?.let { existing ->
            recordClaim(component, existing, claimOf)
            return existing
        }

        // Present but unusable is not absent. A record that is on the disk is the only copy of
        // something, whatever this build can make of it.
        val raw = store.record(component.storeKey)
        if (raw != null) {
            throw IdentityRefusedException(
                reason = "'${component.storeKey}' is in the identity store but could not be " +
                    "used; creating a replacement would write over key material that is still " +
                    "on this device",
                remedy = "inspect the identity store and restore it from a backup; do not " +
                    "delete the existing record",
            )
        }

        when (val verdict = IdentityMintGate.decide(component, verdict(), store.state(), ledger())) {
            is MintVerdict.Refuse -> throw IdentityRefusedException(verdict.reason, verdict.remedy)
            is MintVerdict.AllowedNoteworthy -> log("IdentityCustodian: ${verdict.reason}")
            MintVerdict.AllowedFirstRun -> Unit
        }

        val minted = mint()
        persist(minted)
        recordClaim(component, minted, claimOf)
        return minted
    }

    private fun ledger(): LedgerClaims = cachedLedger ?: ledgerStore.read().also { cachedLedger = it }

    /**
     * Records that [component] exists here. Idempotent, and never fatal: see the class KDoc.
     */
    private fun <T : Any> recordClaim(
        component: IdentityComponent,
        value: T,
        claimOf: (T) -> String,
    ) {
        val current = ledger()
        val base = when (current) {
            LedgerClaims.Unavailable -> return
            is LedgerClaims.Damaged -> {
                // Writing over a damaged ledger could destroy claims that are still in it.
                log(
                    "IdentityCustodian: not recording ${component.claimName}: " +
                        "${ledgerStore.location} is damaged (${current.reasons.joinToString("; ")})",
                )
                return
            }

            LedgerClaims.Absent -> IdentityLedger.create(newEpoch())
            is LedgerClaims.Present -> current
        }

        val claim = try {
            claimOf(value)
        } catch (e: Throwable) {
            log(
                "IdentityCustodian: could not derive the public form of ${component.name} " +
                    "(${e.message}); ${component.claimName} left unrecorded",
            )
            return
        }

        val updated = try {
            IdentityLedger.withClaim(base, component, claim)
        } catch (e: IllegalStateException) {
            // The ledger and the store disagree about which identity this device holds. Loud,
            // and left exactly as it is: whichever of the two was restored from elsewhere, the
            // evidence is worth more than a tidy file.
            log("IdentityCustodian: ${e.message}; ${ledgerStore.location} left unchanged")
            return
        } catch (e: IllegalArgumentException) {
            log("IdentityCustodian: ${e.message}; ${component.claimName} left unrecorded")
            return
        }

        if (updated === base && current is LedgerClaims.Present) return // already recorded

        if (ledgerStore.write(updated)) {
            cachedLedger = updated
            if (current is LedgerClaims.Absent) {
                log("IdentityCustodian: created ${ledgerStore.location}")
            }
        } else {
            log(
                "IdentityCustodian: could not record ${component.claimName} in " +
                    "${ledgerStore.location}; the next start will try again",
            )
        }
    }
}

/**
 * A public, random, write-once identifier for a ledger. Not key material and never used as any:
 * it exists so an operator can tell two ledgers apart.
 */
private fun randomEpoch(): String =
    Random.nextBytes(16).joinToString("") {
        val v = it.toInt() and 0xFF
        HEX[v shr 4].toString() + HEX[v and 0x0F]
    }

private const val HEX = "0123456789abcdef"
