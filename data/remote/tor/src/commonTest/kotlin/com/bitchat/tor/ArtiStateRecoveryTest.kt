package com.bitchat.tor

import com.bitchat.domain.tor.model.TorMode
import com.bitchat.domain.tor.model.TorState
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** The message the Orange Pi logged on 2026-09-30, verbatim. */
internal const val CORRUPT_STATE_ERROR =
    "Arti bootstrap failed: tor: corrupted data in persistent state: Error setting up the guard manager"

/**
 * Lifecycle ERRORs that discarding Arti's state cannot fix, in the exact form `lifecycle.rs` reports
 * them (`tor: <ErrorKind>: <detail>` from arti-client 0.36). None of them may move anything.
 */
internal val UNRELATED_ERRORS = listOf(
    // Bootstrap timeout and a dead network.
    "Arti bootstrap failed: tor: tor operation timed out: Unable to bootstrap a working directory",
    "Arti bootstrap failed: tor: problem with network or connection: Unable to bootstrap a working directory",
    "Arti bootstrap failed: tor: error connecting to Tor: Unable to bootstrap a working directory",
    "Arti bootstrap failed: tor: directory fetch attempt failed: Unable to bootstrap a working directory",
    // The guard manager again, but the state could not be *accessed* (I/O, permissions): moving it
    // would hide the real problem rather than fix it.
    "Arti bootstrap failed: tor: could not read/write persistent state: Error setting up the guard manager",
    "Arti bootstrap failed: tor: problem with filesystem permissions: Error setting up the persistent state manager",
    // Another process holds the state lock.
    "Arti bootstrap failed: tor: local resource (port, lockfile, etc.) already in use: Error setting up the persistent state manager",
    // Corruption somewhere other than the state directory.
    "Arti bootstrap failed: tor: corrupted data in cache: Error setting up the directory manager",
    "Arti bootstrap failed: tor: corrupted data in keystore: Error while trying to access a key store",
    // Wrapper-level failures.
    "Cannot create Arti directories: Permission denied (os error 13)",
    "SOCKS bind failed: Address already in use (os error 98)",
    "SOCKS accept failed: Too many open files (os error 24)",
    "Arti shutdown timed out",
    "Arti start call failed: -3",
)

/**
 * A directory tree laid out the way Arti 1.7 and the app lay it out, in a fresh temporary home:
 *
 *     <home>/.bitchat/prefs/identity              the app's identity keys
 *     <home>/.bitchat/tor/cache/...               Arti's directory cache
 *     <home>/.bitchat/tor/state/keystore/...      Arti's keystore
 *     <home>/.bitchat/tor/state/state/...         tor-persist's FsStateMgr: guards.json, state.lock
 *
 * Only the last may ever move.
 */
internal class ArtiStateFixture {
    val home = Path(SystemTemporaryDirectory, "bitchat-arti-state-" + Random.nextLong().toULong().toString(16))
    val dataDir = Path(home, ".bitchat", "tor")
    val stateDir = Path(dataDir, "state", "state")
    val guards = Path(stateDir, "guards.json")
    private val untouchable = mapOf(
        Path(home, ".bitchat", "prefs", "identity") to "noise and nostr identity keys",
        Path(dataDir, "state", "keystore", "client", "ks_hsc_desc_enc.x25519_private") to "arti keystore entry",
        Path(dataDir, "cache", "dir.sqlite3") to "consensus cache",
    )

    init {
        write(guards, OLD_GUARDS)
        write(Path(stateDir, "state.lock"), "")
        untouchable.forEach { (path, content) -> write(path, content) }
    }

    /** Every `state.stale-*` directory beside the live state directory. */
    fun staleDirectories(): List<Path> =
        SystemFileSystem.list(Path(dataDir, "state")).filter { it.name.startsWith("state.stale-") }.sortedBy { it.name }

    fun assertUntouched() {
        untouchable.forEach { (path, content) ->
            assertTrue(SystemFileSystem.exists(path), "$path must survive")
            assertEquals(content, read(path), "$path must not change")
        }
    }

    fun delete() = deleteRecursively(home)

    companion object {
        const val OLD_GUARDS = """{"guards":"written by an older Arti, 20 KB on the Pi"}"""
        const val NEW_GUARDS = """{"guards":"written by the retried Arti"}"""

        fun write(path: Path, content: String) {
            path.parent?.let { SystemFileSystem.createDirectories(it) }
            SystemFileSystem.sink(path).buffered().use { it.writeString(content) }
        }

        fun read(path: Path): String = SystemFileSystem.source(path).buffered().use { it.readString() }

        fun deleteRecursively(path: Path) {
            val metadata = SystemFileSystem.metadataOrNull(path) ?: return
            if (metadata.isDirectory) SystemFileSystem.list(path).forEach(::deleteRecursively)
            SystemFileSystem.delete(path)
        }
    }
}

@OptIn(ExperimentalTime::class)
class ArtiStateRecoveryTest {
    private val fixture = ArtiStateFixture()
    private val logs = mutableListOf<String>()
    private val now = Instant.parse("2026-09-30T12:34:56.789Z")
    private val firstStale get() = Path(fixture.dataDir, "state", "state.stale-2026-09-30T123456Z")

    @AfterTest
    fun cleanUp() = fixture.delete()

    private fun recovery(
        latch: ArtiRecoveryLatch = ArtiRecoveryLatch(),
        move: (Path, Path) -> Unit = SystemFileSystem::atomicMove,
    ) = ArtiStateRecovery(fixture.dataDir.toString(), latch, move, now = { now }, log = { logs += it })

    @Test
    fun the_corrupt_state_kind_is_recognised_and_nothing_else_is() {
        assertTrue(isCorruptArtiStateError(CORRUPT_STATE_ERROR))
        // Same kind from another consumer of the same state directory.
        assertTrue(isCorruptArtiStateError("Arti bootstrap failed: tor: corrupted data in persistent state: Error setting up the circuit manager"))
        for (message in UNRELATED_ERRORS) {
            assertFalse(isCorruptArtiStateError(message), message)
        }
    }

    @Test
    fun corrupt_state_is_moved_aside_to_a_timestamped_sibling_and_tor_restarts() {
        val outcome = recovery().onLifecycleError(CORRUPT_STATE_ERROR)

        assertIs<ArtiErrorOutcome.Restart>(outcome)
        assertFalse(SystemFileSystem.exists(fixture.stateDir), "the unreadable state must be out of Arti's way")
        assertEquals(listOf(firstStale), fixture.staleDirectories())
        assertEquals(
            ArtiStateFixture.OLD_GUARDS,
            ArtiStateFixture.read(Path(firstStale, "guards.json")),
            "moved, not deleted: the evidence survives",
        )
        fixture.assertUntouched()
        assertTrue(
            logs.any { CORRUPT_STATE_ERROR in it && fixture.stateDir.toString() in it && firstStale.toString() in it },
            "the reset must be logged with the error and both paths: $logs",
        )
    }

    @Test
    fun the_restart_status_is_truthful_and_says_why() {
        val outcome = assertIs<ArtiErrorOutcome.Restart>(recovery().onLifecycleError(CORRUPT_STATE_ERROR))

        val status = outcome.status
        // Restarting, not running and not failed: route waiters keep waiting instead of giving up.
        assertEquals(TorState.STARTING, status.state)
        assertEquals(TorMode.ON, status.mode)
        assertFalse(status.running)
        assertEquals(0, status.bootstrapPercent)
        assertEquals(0, status.socksPort, "no SOCKS port is bound while Arti restarts")
        assertEquals(0L, status.routeGeneration, "no route is owned while Arti restarts")
        // What the settings Tor card shows: the reset, where the old state went, and the restart.
        val notice = assertNotNull(status.errorMessage)
        assertContains(notice, "reset")
        assertContains(notice, "restarting", ignoreCase = true)
        assertContains(notice, firstStale.toString())
        assertEquals(notice, status.lastLogLine)
    }

    @Test
    fun a_second_corrupt_state_error_in_the_same_process_moves_nothing_and_is_surfaced() {
        val recovery = recovery()
        assertIs<ArtiErrorOutcome.Restart>(recovery.onLifecycleError(CORRUPT_STATE_ERROR))
        // The retried Arti writes fresh state and still cannot use it: this Arti is broken, not its state.
        ArtiStateFixture.write(fixture.guards, ArtiStateFixture.NEW_GUARDS)

        val second = recovery.onLifecycleError(CORRUPT_STATE_ERROR)

        val failed = assertIs<ArtiErrorOutcome.Fail>(second)
        assertEquals(ArtiStateFixture.NEW_GUARDS, ArtiStateFixture.read(fixture.guards), "no second move")
        assertEquals(listOf(firstStale), fixture.staleDirectories())
        assertEquals(TorState.ERROR, failed.status.state)
        assertFalse(failed.status.running)
        assertEquals(0L, failed.status.routeGeneration)
        val error = assertNotNull(failed.status.errorMessage)
        assertContains(error, CORRUPT_STATE_ERROR)
        assertContains(error, "already reset")
        fixture.assertUntouched()
    }

    @Test
    fun the_one_reset_is_per_process_not_per_manager() {
        val latch = ArtiRecoveryLatch()
        assertIs<ArtiErrorOutcome.Restart>(recovery(latch).onLifecycleError(CORRUPT_STATE_ERROR))
        ArtiStateFixture.write(fixture.guards, ArtiStateFixture.NEW_GUARDS)

        assertIs<ArtiErrorOutcome.Fail>(recovery(latch).onLifecycleError(CORRUPT_STATE_ERROR))

        assertEquals(ArtiStateFixture.NEW_GUARDS, ArtiStateFixture.read(fixture.guards))
        assertEquals(1, fixture.staleDirectories().size)
    }

    @Test
    fun unrelated_errors_are_surfaced_unchanged_and_leave_the_state_alone() {
        val recovery = recovery()

        for (message in UNRELATED_ERRORS) {
            val failed = assertIs<ArtiErrorOutcome.Fail>(recovery.onLifecycleError(message), message)
            assertEquals(TorState.ERROR, failed.status.state, message)
            assertEquals(message, failed.status.errorMessage)
            assertEquals(message, failed.status.lastLogLine)
        }

        assertEquals(ArtiStateFixture.OLD_GUARDS, ArtiStateFixture.read(fixture.guards))
        assertEquals(emptyList(), fixture.staleDirectories())
        fixture.assertUntouched()
        // None of them used up the one reset this process gets.
        assertIs<ArtiErrorOutcome.Restart>(recovery.onLifecycleError(CORRUPT_STATE_ERROR))
    }

    @Test
    fun an_earlier_stale_directory_is_never_overwritten() {
        // What the hand fix over SSH left behind, at the same second.
        ArtiStateFixture.write(Path(firstStale, "guards.json"), "moved aside by hand")

        assertIs<ArtiErrorOutcome.Restart>(recovery().onLifecycleError(CORRUPT_STATE_ERROR))

        assertEquals("moved aside by hand", ArtiStateFixture.read(Path(firstStale, "guards.json")))
        val second = Path(fixture.dataDir, "state", "state.stale-2026-09-30T123456Z-2")
        assertEquals(listOf(firstStale, second), fixture.staleDirectories())
        assertEquals(ArtiStateFixture.OLD_GUARDS, ArtiStateFixture.read(Path(second, "guards.json")))
        fixture.assertUntouched()
    }

    @Test
    fun a_move_that_fails_is_surfaced_and_never_retried() {
        val latch = ArtiRecoveryLatch()
        val refused: (Path, Path) -> Unit = { _, _ -> throw IOException("Permission denied") }

        val failed = assertIs<ArtiErrorOutcome.Fail>(recovery(latch, refused).onLifecycleError(CORRUPT_STATE_ERROR))

        val error = assertNotNull(failed.status.errorMessage)
        assertContains(error, CORRUPT_STATE_ERROR)
        assertContains(error, "Permission denied")
        assertEquals(ArtiStateFixture.OLD_GUARDS, ArtiStateFixture.read(fixture.guards))
        // The attempt is spent even though it failed: no later error gets a second one.
        assertIs<ArtiErrorOutcome.Fail>(recovery(latch).onLifecycleError(CORRUPT_STATE_ERROR))
        assertEquals(emptyList(), fixture.staleDirectories())
        fixture.assertUntouched()
    }

    @Test
    fun a_missing_state_directory_is_reported_rather_than_restarted() {
        ArtiStateFixture.deleteRecursively(fixture.stateDir)

        val failed = assertIs<ArtiErrorOutcome.Fail>(recovery().onLifecycleError(CORRUPT_STATE_ERROR))

        val error = assertNotNull(failed.status.errorMessage)
        assertContains(error, CORRUPT_STATE_ERROR)
        assertContains(error, fixture.stateDir.toString())
        assertEquals(emptyList(), fixture.staleDirectories())
        fixture.assertUntouched()
    }
}
