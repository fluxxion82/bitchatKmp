package com.bitchat.local.identity

/**
 * What kind of file was found in the identity domain.
 *
 * The bias is deliberate and one-directional: an unrecognised name is [OTHER], and [OTHER] is
 * still an artifact. A name this build does not know about is evidence that *something* has
 * been here, which is exactly what "this is not a first run" means.
 */
enum class ArtifactClass {
    /** `<name>.prefs.enc` - a sealed store. */
    SEALED_STORE,

    /** `<name>.prefs` - a plaintext store. */
    PLAINTEXT_STORE,

    /** `<name>.prefs.plaintext.bak` - a retained plaintext copy from some earlier attempt. */
    RETAINED_PLAINTEXT,

    /** `<name>.prefs.tmpXXXXXX` - a write that was interrupted before its rename. */
    TEMP_WRITE,

    /** The at-rest master key. */
    MASTER_KEY,

    /** The identity ledger. */
    LEDGER,

    /** Anything else at all. Unknown is not the same as harmless. */
    OTHER,
}

/**
 * One file found in the identity domain.
 *
 * Carries a location and a classification and **nothing else**: the scan never reads a byte of
 * any file, so this type has nowhere to put file content even by accident.
 */
data class DomainArtifact(
    val directory: String,
    val name: String,
    val kind: ArtifactClass,
)

/**
 * The result of trying to list one directory of the identity domain.
 *
 * @param path the directory that was listed.
 * @param existed false when the directory is not there at all (`ENOENT`), which is positive
 *   evidence of absence.
 * @param names every entry found, excluding `.` and `..`. **Null exactly when the directory
 *   exists and could not be listed** - which is evidence of nothing whatsoever.
 * @param failure why the listing failed, when it did.
 */
data class DirectoryListing(
    val path: String,
    val existed: Boolean,
    val names: List<String>?,
    val failure: String? = null,
) {
    companion object {
        fun absent(path: String) = DirectoryListing(path, existed = false, names = emptyList())
        fun listed(path: String, names: List<String>) =
            DirectoryListing(path, existed = true, names = names)

        fun unlistable(path: String, failure: String) =
            DirectoryListing(path, existed = true, names = null, failure = failure)
    }
}

/**
 * Whether the identity domain proves this is a genuine first run.
 *
 * The whole point of this type is that only [Virgin] is proof, and [Virgin] is reached by
 * looking, never by failing to find.
 */
sealed interface DomainVerdict {

    /** Positive proof of a first run: every directory was read, and every one was empty. */
    data object Virgin : DomainVerdict

    /** Something this application creates is already here. */
    data class Inhabited(val artifacts: List<DomainArtifact>) : DomainVerdict

    /**
     * A directory exists and could not be listed. Never treated as evidence of absence: a
     * directory we cannot read may hold the only copy of an identity.
     */
    data class Indeterminate(val reasons: List<String>) : DomainVerdict

    /** Platforms with no directory domain at all (Android, Apple, JVM desktop). */
    data object NotApplicable : DomainVerdict
}
