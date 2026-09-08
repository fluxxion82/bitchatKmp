package com.bitchat.local.identity

/**
 * Turns directory listings into a [DomainVerdict].
 *
 * Pure: it classifies by directory and file name only and never reads a byte of any file.
 * That is what lets the whole first-run decision be tested on a developer machine even though
 * its only production caller is Kotlin/Native `linuxArm64`, which cannot be executed there.
 *
 * The identity domain is three directories - the preferences directory, the config directory
 * and the (non-secret) settings directory. The settings directory holds no secrets, but a file
 * in it still proves this application has run on this machine before, which is precisely the
 * question being asked.
 */
object DomainScan {

    /** `readdir` yields these; they are not artifacts. Filtered again here, defensively. */
    private val SELF_AND_PARENT = setOf(".", "..")

    /**
     * Classifies [listings] into a verdict.
     *
     * [DomainVerdict.Virgin] is returned only when every listing reports either "does not
     * exist" or a completed, empty listing. One unlistable directory makes the whole domain
     * [DomainVerdict.Indeterminate], whatever the others said, because the unread directory
     * could hold anything.
     */
    fun verdict(listings: List<DirectoryListing>): DomainVerdict {
        val unreadable = listings.filter { it.names == null }
        if (unreadable.isNotEmpty()) {
            return DomainVerdict.Indeterminate(
                unreadable.map { "${it.path}: ${it.failure ?: "could not be listed"}" },
            )
        }

        val artifacts = listings.flatMap { listing ->
            listing.names.orEmpty()
                .filterNot { it in SELF_AND_PARENT }
                .map { DomainArtifact(listing.path, it, classify(it)) }
        }

        return if (artifacts.isEmpty()) DomainVerdict.Virgin else DomainVerdict.Inhabited(artifacts)
    }

    /**
     * Names this application is known to create. Order matters: the longer suffixes are tested
     * first, and everything unrecognised falls through to [ArtifactClass.OTHER].
     */
    fun classify(name: String): ArtifactClass = when {
        // An interrupted mkstemp write: "<something>.tmpAb12Cd". Checked first, because the
        // suffix sits after the .prefs / .prefs.enc part of the name.
        TEMP_SUFFIX.containsMatchIn(name) -> ArtifactClass.TEMP_WRITE
        name == MASTER_KEY_NAME -> ArtifactClass.MASTER_KEY
        name == IdentityLedger.FILE_NAME -> ArtifactClass.LEDGER
        name.endsWith(".prefs.plaintext.bak") -> ArtifactClass.RETAINED_PLAINTEXT
        name.endsWith(".prefs.enc") -> ArtifactClass.SEALED_STORE
        name.endsWith(".prefs") -> ArtifactClass.PLAINTEXT_STORE
        else -> ArtifactClass.OTHER
    }

    /** The at-rest master key this plan's later tasks create. Recognised now so that a file
     *  left behind by a half-finished later task already counts as evidence. */
    const val MASTER_KEY_NAME: String = "master.key"

    private val TEMP_SUFFIX = Regex("""\.tmp[A-Za-z0-9]*$""")
}
