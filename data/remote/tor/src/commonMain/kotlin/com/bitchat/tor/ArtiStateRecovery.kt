package com.bitchat.tor

import com.bitchat.domain.tor.model.TorMode
import com.bitchat.domain.tor.model.TorState
import com.bitchat.domain.tor.model.TorStatus
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * True for the one lifecycle ERROR that moving Arti's saved state aside can cure: Arti found a file
 * in its state directory that it could not parse.
 *
 * `lifecycle.rs` reports bootstrap failures as `Arti bootstrap failed: <error>`, and arti-client
 * renders an error as `tor: <ErrorKind>: <detail>`. The text matched here is the display of
 * `ErrorKind::PersistentStateCorrupted`, which tor-persist raises only when a state file fails to
 * deserialize, and which tor-error documents as covering the state directory alone - including
 * "the Tor code was upgraded and the new Tor is not compatible", i.e. a guards.json an older Arti
 * wrote. On 2026-09-30 an Orange Pi reported exactly
 * `Arti bootstrap failed: tor: corrupted data in persistent state: Error setting up the guard manager`.
 *
 * Deliberately not matched, because discarding guard state cures none of them and some would lose
 * evidence or identity: a bootstrap timeout or dead network (`tor operation timed out`, `problem
 * with network or connection`, ...), state Arti could not read or write at all (`could not
 * read/write persistent state`, `problem with filesystem permissions`), a state lock held by another
 * process (`local resource ... already in use`), and a corrupt cache or keystore.
 */
internal fun isCorruptArtiStateError(message: String): Boolean =
    message.contains(CORRUPT_STATE_KIND, ignoreCase = true)

private const val CORRUPT_STATE_KIND = "corrupted data in persistent state"

/**
 * The single state reset a process gets. Shared by every [ArtiStateRecovery] through [process], so
 * even a second TorManager in the same process cannot reset again; tests hand each case its own.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class ArtiRecoveryLatch {
    private val spent = AtomicBoolean(false)

    /** True exactly once: for the caller that gets to reset. */
    fun tryAcquire(): Boolean = spent.compareAndSet(expectedValue = false, newValue = true)

    companion object {
        val process = ArtiRecoveryLatch()
    }
}

/** What a lifecycle ERROR from Arti turns into. [status] is published either way. */
internal sealed interface ArtiErrorOutcome {
    val status: TorStatus

    /** Arti's state was moved aside: publish [status], then start a new generation. */
    data class Restart(override val status: TorStatus) : ArtiErrorOutcome

    /** Tor stays down: publish [status]. */
    data class Fail(override val status: TorStatus) : ArtiErrorOutcome
}

/** The status every Arti lifecycle failure publishes: off, no port, no route, and why. */
internal fun artiErrorStatus(message: String): TorStatus = TorStatus(
    mode = TorMode.OFF,
    state = TorState.ERROR,
    socksPort = 0,
    lastLogLine = message,
    errorMessage = message,
    routeGeneration = 0,
)

/**
 * Gets Tor going again, by itself, when Arti cannot read its own saved state.
 *
 * On 2026-09-30 a board upgraded to a newer Arti kept the guards.json an older Arti had written.
 * Every bootstrap then failed with [isCorruptArtiStateError], and with Tor on the route provider
 * correctly refused every relay connection, so geohash channels and Nostr DMs stayed dead until
 * someone moved the state directory aside over SSH. This makes that same move once and asks for a
 * restart.
 *
 * What moves: `<dataDir>/state/state`, the directory tor-persist's FsStateMgr owns (guards.json,
 * the rest of Arti's JSON state, and its lock file). It is renamed, never deleted, to a sibling
 * `state.stale-<UTC time>` so the evidence survives. Nothing else is touched: not
 * `<dataDir>/state/keystore` (Arti's keys), not `<dataDir>/cache` (which has its own corruption
 * kind), and nothing outside [dataDir] - in particular not `~/.bitchat/prefs`, where the identity
 * keys live.
 *
 * At most one reset per process ([ArtiRecoveryLatch.process]), spent before the move so a failed
 * move cannot be retried either. If the restarted Arti cannot read the state it writes itself, the
 * fault is Arti's, and moving again would only turn it into a wipe-and-retry loop.
 *
 * Not thread-safe beyond the latch: callers report one error at a time, as each TorManager already
 * applies one status at a time.
 */
@OptIn(ExperimentalTime::class)
internal class ArtiStateRecovery(
    private val dataDir: String,
    private val latch: ArtiRecoveryLatch = ArtiRecoveryLatch.process,
    private val move: (from: Path, to: Path) -> Unit = SystemFileSystem::atomicMove,
    private val now: () -> Instant = { Clock.System.now() },
    private val log: (String) -> Unit = ::println,
) {
    private val stateParent = Path(dataDir, "state")
    private val stateDir = Path(stateParent, "state")

    /** Decides what the current generation's lifecycle ERROR [message] means. */
    fun onLifecycleError(message: String): ArtiErrorOutcome {
        if (!isCorruptArtiStateError(message)) return ArtiErrorOutcome.Fail(artiErrorStatus(message))

        if (!latch.tryAcquire()) {
            return fail(
                "$message - Tor state was already reset once since the app started, and is not reset " +
                    "twice. Restart the app to try again."
            )
        }
        if (!SystemFileSystem.exists(stateDir)) {
            return fail("$message - there is no Arti state at $stateDir to reset.")
        }
        val stale = staleDestination()
        try {
            move(stateDir, stale)
        } catch (e: IOException) {
            return fail("$message - could not move $stateDir aside to $stale: ${e.message}")
        }

        log("Arti could not read its saved state ($message). Moved $stateDir to $stale and restarting Tor; this happens at most once per run.")
        val notice = "Tor state could not be read, so it was reset (moved aside to $stale). Restarting Tor..."
        return ArtiErrorOutcome.Restart(
            TorStatus(
                mode = TorMode.ON,
                running = false,
                bootstrapPercent = 0,
                state = TorState.STARTING,
                socksPort = 0,
                lastLogLine = notice,
                errorMessage = notice,
                routeGeneration = 0,
            )
        )
    }

    private fun fail(detail: String): ArtiErrorOutcome.Fail {
        log(detail)
        return ArtiErrorOutcome.Fail(artiErrorStatus(detail))
    }

    /** `state.stale-2026-09-30T123456Z`, or with `-2`, `-3`... if an earlier one has that name. */
    private fun staleDestination(): Path {
        val stamp = Instant.fromEpochSeconds(now().epochSeconds).toString().replace(":", "")
        val base = "state.stale-$stamp"
        var candidate = Path(stateParent, base)
        var suffix = 2
        while (SystemFileSystem.exists(candidate)) {
            candidate = Path(stateParent, "$base-$suffix")
            suffix += 1
        }
        return candidate
    }
}
