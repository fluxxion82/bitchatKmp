package com.bitchat.local.identity

import com.bitchat.local.prefs.FlatFileFormat

/** What the identity ledger had to say. */
sealed interface LedgerClaims {

    /** No ledger file exists. */
    data object Absent : LedgerClaims

    /**
     * A ledger exists and is not trustworthy. Never read as "no claims": a claim that cannot be
     * read is not a claim that was never made.
     */
    data class Damaged(val reasons: List<String>) : LedgerClaims

    /**
     * A ledger that parsed.
     *
     * @param epoch a random, public, write-once identifier for this ledger.
     * @param claims every non-`version`, non-`epoch` record, keyed by its record name (e.g.
     *   `claim.nostr`), carried through untouched whether or not this build understands it.
     */
    data class Present(val epoch: String, val claims: Map<String, String>) : LedgerClaims

    /** Platforms that keep no ledger (Android, Apple, JVM desktop). */
    data object Unavailable : LedgerClaims
}

/**
 * A plaintext, public record of which identity components this device has ever created.
 *
 * ## Why it exists
 *
 * A flat preference file truncated *exactly at a record boundary* parses cleanly - the decoder
 * only reports damage when the file does not end in a newline - so a truncation that drops the
 * trailing Nostr records while keeping the earlier mesh records reads as "store loaded fine,
 * holds other keys, but not mine". That is byte-for-byte the state a genuine first run passes
 * through, because the Bluetooth module writes the mesh signing key into that same store before
 * Nostr is ever consulted. From the store's contents alone the two are indistinguishable.
 *
 * The discriminator has to live **outside** the file that might have lost the record. This is
 * it: a claim recorded here, plus that component absent from the store, means the component was
 * lost, not never created.
 *
 * ## What it holds
 *
 * Public values only - the same public keys that are already broadcast on the mesh and the
 * relays - plus a random epoch. It is plaintext and stays plaintext even after the stores are
 * sealed, deliberately: its job is to be readable when the sealed store is not, and greppable
 * by an operator holding nothing but a recovered card.
 *
 * ```
 * version=1
 * epoch=<32 hex, random, written once when the ledger is created>
 * claim.mesh_static=<x25519 public key, hex>
 * claim.mesh_signing=<ed25519 public key, hex>
 * claim.nostr=<x-only public key, hex>
 * claim.nostr_seed=present
 * ```
 *
 * `claim.nostr_seed` is the literal string [CLAIM_PRESENT] because the device seed has no public
 * form, and a digest of it would be a secret-adjacent artifact for no gain.
 *
 * ## Ordering
 *
 * A claim is recorded **after** the component it describes is saved, never before. The two
 * failure directions are not symmetric: a claim with no key is a permanent refusal to mint on a
 * device that has no identity - a brick - whereas a key with no claim is found on the next
 * start, is not minted over, and gets its claim recorded on the way past. Under-claiming is
 * self-healing; over-claiming is not.
 */
object IdentityLedger {

    const val FILE_NAME: String = "identity-ledger"
    const val VERSION: String = "1"
    const val KEY_VERSION: String = "version"
    const val KEY_EPOCH: String = "epoch"

    /** The claim value for a component that has no public form. */
    const val CLAIM_PRESENT: String = "present"

    /**
     * Reads a ledger through [FlatFileFormat], so damage detection is code that is already
     * tested and already deployed.
     *
     * @param text the whole file, or null when there is no file.
     */
    fun parse(text: String?): LedgerClaims {
        if (text == null) return LedgerClaims.Absent

        val content = FlatFileFormat.decode(text)
        val reasons = content.damage.toMutableList()

        val version = content.entries[KEY_VERSION]
        val epoch = content.entries[KEY_EPOCH]
        if (version == null) {
            reasons += "no '$KEY_VERSION' record"
        } else if (version != VERSION) {
            reasons += "unknown $KEY_VERSION '$version' (this build writes $VERSION)"
        }
        if (epoch.isNullOrEmpty()) reasons += "no '$KEY_EPOCH' record"

        // Fail closed. An empty claim map returned because the file could not be understood
        // would read as "this device has never created anything", which is the one conclusion
        // a damaged ledger must never be allowed to support.
        if (reasons.isNotEmpty()) return LedgerClaims.Damaged(reasons)

        val claims = content.entries
            .filterKeys { it != KEY_VERSION && it != KEY_EPOCH }
            .toMap(LinkedHashMap())
        return LedgerClaims.Present(epoch = epoch!!, claims = claims)
    }

    /** Serialises [claims] in the same flat format, version and epoch first. */
    fun encode(claims: LedgerClaims.Present): String {
        val entries = LinkedHashMap<String, String>()
        entries[KEY_VERSION] = VERSION
        entries[KEY_EPOCH] = claims.epoch
        entries.putAll(claims.claims)
        return FlatFileFormat.encode(entries)
    }

    /** A brand new ledger with no claims. [epoch] is public and random. */
    fun create(epoch: String): LedgerClaims.Present =
        LedgerClaims.Present(epoch = epoch, claims = emptyMap())

    /** The recorded claim for [component], or null when this device has never claimed it. */
    fun claim(claims: LedgerClaims.Present, component: IdentityComponent): String? =
        claims.claims[component.claimName]

    /**
     * Records that [component] exists on this device, with public value [value].
     *
     * Idempotent: re-recording the same value returns [claims] unchanged, so a claim can safely
     * be re-asserted on every successful load. Recording a *different* value throws, because a
     * component whose public form has changed is either a restored ledger from another device
     * or a restored store from another device, and silently rewriting the claim would erase the
     * evidence of whichever it is.
     */
    fun withClaim(
        claims: LedgerClaims.Present,
        component: IdentityComponent,
        value: String,
    ): LedgerClaims.Present {
        require(value.isNotEmpty()) { "a claim value may not be empty" }
        val existing = claims.claims[component.claimName]
        if (existing == value) return claims
        check(existing == null) {
            "${component.claimName} is already claimed as '$existing'; refusing to overwrite it " +
                "with '$value'"
        }
        val updated = LinkedHashMap(claims.claims)
        updated[component.claimName] = value
        return claims.copy(claims = updated)
    }
}
