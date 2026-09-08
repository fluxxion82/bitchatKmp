package com.bitchat.local.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val PREFS = "/home/sterling/.bitchat/prefs"
private const val CONFIG = "/home/sterling/.config/bitchat"
private const val SETTINGS = "/home/sterling/.bitchat/settings"

private fun domain(
    prefs: List<String> = emptyList(),
    config: List<String> = emptyList(),
    settings: List<String> = emptyList(),
) = DomainScan.verdict(
    listOf(
        DirectoryListing.listed(PREFS, prefs),
        DirectoryListing.listed(CONFIG, config),
        DirectoryListing.listed(SETTINGS, settings),
    ),
)

class DomainScanTest {

    @Test
    fun `three present, empty directories are positive proof of a first run`() {
        assertEquals(DomainVerdict.Virgin, domain())
    }

    @Test
    fun `three absent directories are positive proof of a first run`() {
        val verdict = DomainScan.verdict(
            listOf(
                DirectoryListing.absent(PREFS),
                DirectoryListing.absent(CONFIG),
                DirectoryListing.absent(SETTINGS),
            ),
        )

        assertEquals(DomainVerdict.Virgin, verdict)
    }

    @Test
    fun `one unlistable directory is indeterminate, never virgin`() {
        // The single most important negative case: a directory we could not read may hold the
        // only copy of an identity, so it can never contribute to a "nothing is here" verdict.
        val verdict = DomainScan.verdict(
            listOf(
                DirectoryListing.listed(PREFS, emptyList()),
                DirectoryListing.unlistable(CONFIG, "Permission denied (errno 13)"),
                DirectoryListing.listed(SETTINGS, emptyList()),
            ),
        )

        val indeterminate = assertIs<DomainVerdict.Indeterminate>(verdict)
        assertEquals(1, indeterminate.reasons.size)
        assertTrue(indeterminate.reasons.single().contains(CONFIG), indeterminate.reasons.toString())
        assertTrue(
            indeterminate.reasons.single().contains("Permission denied"),
            indeterminate.reasons.toString(),
        )
    }

    @Test
    fun `an unlistable directory outweighs artifacts found elsewhere`() {
        val verdict = DomainScan.verdict(
            listOf(
                DirectoryListing.listed(PREFS, listOf("bitchat_identity.prefs")),
                DirectoryListing.unlistable(CONFIG, "Permission denied"),
                DirectoryListing.absent(SETTINGS),
            ),
        )

        assertIs<DomainVerdict.Indeterminate>(verdict)
    }

    @Test
    fun `a plaintext store makes the domain inhabited`() {
        val verdict = assertIs<DomainVerdict.Inhabited>(domain(prefs = listOf("bitchat_identity.prefs")))

        assertEquals(
            listOf(DomainArtifact(PREFS, "bitchat_identity.prefs", ArtifactClass.PLAINTEXT_STORE)),
            verdict.artifacts,
        )
    }

    @Test
    fun `a sealed store makes the domain inhabited`() {
        val verdict = assertIs<DomainVerdict.Inhabited>(
            domain(prefs = listOf("bitchat_identity.prefs.enc")),
        )

        assertEquals(ArtifactClass.SEALED_STORE, verdict.artifacts.single().kind)
    }

    @Test
    fun `a retained plaintext backup alone makes the domain inhabited`() {
        // Round-two review finding 6: a retained backup present while the main file is absent
        // was read as a first run.
        val verdict = assertIs<DomainVerdict.Inhabited>(
            domain(prefs = listOf("bitchat_identity.prefs.plaintext.bak")),
        )

        assertEquals(ArtifactClass.RETAINED_PLAINTEXT, verdict.artifacts.single().kind)
    }

    @Test
    fun `an orphaned temp file alone makes the domain inhabited`() {
        val verdict = assertIs<DomainVerdict.Inhabited>(
            domain(prefs = listOf("bitchat_identity.prefs.tmpAb12Cd")),
        )

        assertEquals(ArtifactClass.TEMP_WRITE, verdict.artifacts.single().kind)
    }

    @Test
    fun `a master key alone makes the domain inhabited`() {
        val verdict = assertIs<DomainVerdict.Inhabited>(domain(config = listOf("master.key")))

        assertEquals(ArtifactClass.MASTER_KEY, verdict.artifacts.single().kind)
    }

    @Test
    fun `a ledger alone makes the domain inhabited`() {
        val verdict = assertIs<DomainVerdict.Inhabited>(
            domain(config = listOf(IdentityLedger.FILE_NAME)),
        )

        assertEquals(ArtifactClass.LEDGER, verdict.artifacts.single().kind)
    }

    @Test
    fun `a file in the settings directory alone makes the domain inhabited`() {
        // No secrets live there, but its existence proves this application has run here before,
        // which is the only question the scan is asking.
        val verdict = assertIs<DomainVerdict.Inhabited>(
            domain(settings = listOf("appPreferences.prefs")),
        )

        assertEquals(SETTINGS, verdict.artifacts.single().directory)
    }

    @Test
    fun `an unrecognised name is still an artifact`() {
        val verdict = assertIs<DomainVerdict.Inhabited>(domain(prefs = listOf("something-new")))

        assertEquals(ArtifactClass.OTHER, verdict.artifacts.single().kind)
    }

    @Test
    fun `dot and dot-dot are not artifacts`() {
        // readdir yields them for every directory that exists; counting them would make every
        // existing directory inhabited and no device could ever be a first run.
        assertEquals(DomainVerdict.Virgin, domain(prefs = listOf(".", "..")))
    }

    @Test
    fun `an artifact can hold a location and a classification and nothing else`() {
        // The scan never opens a file. This asserts it has nowhere to put file content even by
        // accident, so a verdict can be logged in full without leaking a key.
        val fields = DomainArtifact::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .map { it.name }
            .toSet()

        assertEquals(setOf("directory", "name", "kind"), fields)
    }
}
