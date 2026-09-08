package com.bitchat.local.identity

import com.bitchat.local.prefs.PreferenceStoreState

/** What to do when a component of the device identity is not in the store. */
sealed interface MintVerdict {

    /** Create it. Nothing was ever stored here. */
    data object AllowedFirstRun : MintVerdict

    /** Create it, but say why this was not obviously a first run. */
    data class AllowedNoteworthy(val reason: String) : MintVerdict

    /**
     * Do not create it.
     *
     * @param reason what was seen, in the operator's terms.
     * @param remedy a command, or the shape of one, that a human can act on.
     */
    data class Refuse(val reason: String, val remedy: String) : MintVerdict
}

/**
 * The one rule, as a pure function:
 *
 * > **Minting identity material requires positive proof of a genuine first run. The absence of
 * > one file is never such proof.**
 *
 * Positive proof is established by looking, not by failing to find:
 *
 *  1. every directory of the identity domain was either absent or listed to completion;
 *  2. nothing this application creates was found in any of them;
 *  3. the store being read reported no damage;
 *  4. the identity ledger holds no claim for the component about to be created.
 *
 * If any of those fails, the answer is refuse and report - never mint.
 *
 * ## Platforms other than the embedded Linux build
 *
 * Android, Apple and JVM desktop have no directory domain to scan and keep no ledger: their
 * stores are the Keychain and EncryptedSharedPreferences, which surface a read failure as an
 * exception rather than as a half-loaded map. They pass [DomainVerdict.NotApplicable] and
 * [LedgerClaims.Unavailable], and in exactly that configuration this gate reproduces the
 * pre-custodian three-way decision (`com.bitchat.nostr.NostrIdentityMintPolicy`) verbatim, so
 * this work changes no behaviour anywhere but `linuxArm64`. `IdentityMintGateTest` asserts that
 * equivalence against that class rather than against a copy of its table.
 */
object IdentityMintGate {

    fun decide(
        component: IdentityComponent,
        domain: DomainVerdict,
        store: PreferenceStoreState,
        ledger: LedgerClaims,
    ): MintVerdict {
        val key = component.storeKey

        // 1. The no-domain, no-ledger platforms, reproduced exactly.
        if (domain == DomainVerdict.NotApplicable && ledger == LedgerClaims.Unavailable) {
            return legacy(key, store)
        }

        // 2. Half a configuration is not a configuration. A platform that scans a domain but
        //    keeps no ledger (or the reverse) has been wired up wrong, and the safe reading of
        //    a wiring mistake is "I do not know what is on this disk".
        if (domain == DomainVerdict.NotApplicable || ledger == LedgerClaims.Unavailable) {
            return MintVerdict.Refuse(
                reason = "identity guard is half-configured: domain=$domain, ledger=$ledger; " +
                    "refusing to create '$key' without knowing what is already on disk",
                remedy = "register a DomainInspector and a LedgerStore that agree with each " +
                    "other in this platform's localModule",
            )
        }

        // 3. A damaged ledger is never read as "no claims".
        if (ledger is LedgerClaims.Damaged) {
            return MintVerdict.Refuse(
                reason = "the identity ledger is damaged (${ledger.reasons.joinToString("; ")}), " +
                    "so it cannot say whether '$key' has existed on this device before",
                remedy = "repair or restore the identity ledger from a backup before starting " +
                    "again; do not delete it",
            )
        }

        // 4. A directory that exists and cannot be listed is evidence of nothing.
        if (domain is DomainVerdict.Indeterminate) {
            return MintVerdict.Refuse(
                reason = "part of the identity domain could not be read " +
                    "(${domain.reasons.joinToString("; ")}), so this may not be a first run",
                remedy = "make those directories readable by the service user and start again",
            )
        }

        // 5. The truncation case. A claim recorded for this component, and the component not in
        //    the store, means it was lost - recovered, never replaced.
        if (ledger is LedgerClaims.Present) {
            val claimed = IdentityLedger.claim(ledger, component)
            if (claimed != null) {
                return MintVerdict.Refuse(
                    reason = "this device has already created ${component.name} " +
                        "(${component.claimName}=$claimed in the identity ledger) but '$key' is " +
                        "not in the store; the private key must be recovered, not replaced",
                    remedy = "restore the identity store from a backup; creating a replacement " +
                        "would abandon the identity this device already published",
                )
            }
        }

        // 6. A store that did not load cleanly may simply have lost this record.
        if (store == PreferenceStoreState.UNREADABLE) {
            return MintVerdict.Refuse(
                reason = UNREADABLE_REASON(key),
                remedy = "inspect the identity store and restore it from a backup if it is " +
                    "damaged; do not delete it",
            )
        }

        return when (ledger) {
            // 7. No ledger yet. Only a domain that was read and found empty is proof.
            LedgerClaims.Absent -> when (domain) {
                DomainVerdict.Virgin ->
                    if (store == PreferenceStoreState.FIRST_RUN) {
                        MintVerdict.AllowedFirstRun
                    } else {
                        // Unreachable: a virgin domain has no store file to be populated from.
                        MintVerdict.AllowedNoteworthy(
                            "the identity domain is empty but the store already holds keys; " +
                                "creating '$key'",
                        )
                    }

                is DomainVerdict.Inhabited -> MintVerdict.Refuse(
                    reason = "this device has run before (${describe(domain)}) but keeps no " +
                        "identity ledger, so nothing here can prove '$key' was never created",
                    remedy = "run the binary with --identity-adopt to write the ledger from " +
                        "what is on disk, then start again",
                )

                // Both handled above; repeated so the compiler keeps this exhaustive.
                DomainVerdict.NotApplicable, is DomainVerdict.Indeterminate ->
                    MintVerdict.Refuse(
                        reason = "unreachable domain verdict $domain while creating '$key'",
                        remedy = "report this: the identity gate reached a state it excludes",
                    )
            }

            // 8. A clean ledger with no claim for this component: it has genuinely never
            //    existed here, whatever else the store happens to hold.
            is LedgerClaims.Present -> when (store) {
                PreferenceStoreState.FIRST_RUN -> MintVerdict.AllowedFirstRun
                PreferenceStoreState.POPULATED ->
                    MintVerdict.AllowedNoteworthy(POPULATED_REASON(key))
                // Refused at step 6.
                PreferenceStoreState.UNREADABLE -> MintVerdict.Refuse(
                    reason = UNREADABLE_REASON(key),
                    remedy = "inspect the identity store and restore it from a backup",
                )
            }

            // Both excluded above.
            LedgerClaims.Unavailable, is LedgerClaims.Damaged -> MintVerdict.Refuse(
                reason = "unreachable ledger state $ledger while creating '$key'",
                remedy = "report this: the identity gate reached a state it excludes",
            )
        }
    }

    /**
     * The decision this code made before the domain scan and the ledger existed, kept verbatim
     * for the platforms that have neither.
     *
     * `POPULATED` mints, and that is correct here: the Bluetooth module writes the mesh signing
     * key into this same store while Koin builds the graph, before Nostr is ever asked for its
     * key, so "other keys but not this one" is a state every genuine new device passes through.
     * It is also what a partially lost store looks like, which is the whole reason the embedded
     * build now has a ledger to tell the two apart.
     */
    private fun legacy(key: String, store: PreferenceStoreState): MintVerdict = when (store) {
        PreferenceStoreState.FIRST_RUN -> MintVerdict.AllowedFirstRun
        PreferenceStoreState.POPULATED -> MintVerdict.AllowedNoteworthy(POPULATED_REASON(key))
        PreferenceStoreState.UNREADABLE -> MintVerdict.Refuse(
            reason = UNREADABLE_REASON(key),
            remedy = "inspect the identity store and restore it from a backup if it is damaged",
        )
    }

    private fun describe(domain: DomainVerdict.Inhabited): String =
        domain.artifacts.take(4).joinToString(", ") { "${it.directory}/${it.name}" } +
            if (domain.artifacts.size > 4) " and ${domain.artifacts.size - 4} more" else ""

    private val POPULATED_REASON: (String) -> String =
        { key -> "identity store holds other keys but no '$key'; creating one" }

    private val UNREADABLE_REASON: (String) -> String = { key ->
        "identity store did not load cleanly, so '$key' may exist on disk; " +
            "refusing to create a replacement that would overwrite it"
    }
}
