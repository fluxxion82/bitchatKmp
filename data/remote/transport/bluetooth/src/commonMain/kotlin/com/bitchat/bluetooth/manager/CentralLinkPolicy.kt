package com.bitchat.bluetooth.manager

/**
 * When this node may open an outbound BLE link, and when it must give up on one it has started.
 *
 * A BLE controller has a single initiator. It cannot scan and connect at the same time, and it
 * cannot have two connection attempts outstanding: asking it to start a second one makes the host
 * cancel the first, which BlueZ reports back as
 * `org.bluez.Error.Failed: le-connection-abort-by-local`. The device journal shows exactly that for
 * every failed connect, roughly seventeen seconds after the request, while an indefinite scan runs
 * across the whole attempt.
 *
 * The previous policy was "connect to everything the scanner offers, immediately". Because Android
 * rotates its advertising address, the scanner offers the same phone again every minute or so under
 * a new MAC, so attempts overlapped continuously and the ones that did succeed were torn down
 * within seconds. Worse, gattlib's own connect timeout does not fail the attempt — `_stop_connect_func`
 * in `dbus/gattlib.c` only clears the timer — so an attempt BlueZ accepts but never resolves
 * services for produces no callback at all and the caller's registry grows without bound: the
 * journal shows it reaching seven while no link was up.
 *
 * So this class enforces three things, and owns the deadline gattlib does not:
 *
 *  - at most [maxConcurrentConnects] attempts in flight, so the initiator is never contended;
 *  - at most [maxCentralLinks] outbound links, so the controller's connection budget is not spent
 *    on stale addresses of one phone;
 *  - an attempt that has produced no callback within [connectTimeoutMs] is abandoned, which is the
 *    only thing that ever ends one.
 *
 * It is pure bookkeeping with no radio and no clock — callers pass `now` in epoch milliseconds — so
 * the rules can be tested on the JVM; the linuxArm64 target they run on is cross-compiled and
 * cannot run its own tests.
 */
class CentralLinkPolicy(
    private val maxConcurrentConnects: Int = MAX_CONCURRENT_CONNECTS,
    private val maxCentralLinks: Int = MAX_CENTRAL_LINKS,
    private val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
    private val baseBackoffMs: Long = BASE_BACKOFF_MS,
    private val maxBackoffMs: Long = MAX_BACKOFF_MS
) {

    /** Why a discovered device was not connected to, or that it should be. */
    sealed interface Decision {
        data object Connect : Decision
        data class Skip(val reason: String) : Decision
    }

    private val pending = mutableMapOf<String, Long>()
    private val established = mutableSetOf<String>()

    // When this address last stopped being worked on: the later of the attempt starting and the
    // attempt ending. The backoff runs from the end, not the start, because an attempt can occupy
    // the radio for a long time -- `Device.Connect()` blocks for the full 25s D-Bus timeout and
    // BlueZ carries on connecting afterwards, so a backoff measured from the start had already
    // elapsed by the time the failure was reported and the retry came straight back as
    // `org.bluez.Error.InProgress`.
    private val lastActivity = mutableMapOf<String, Long>()
    private val attemptCount = mutableMapOf<String, Int>()

    /**
     * Whether to open an outbound link to [address], discovered at [now].
     *
     * [inboundAddresses] are the centrals already connected to our GATT server and [peerAddresses]
     * the other addresses of the peer behind this one, if it is already known. Both are reasons not
     * to connect: BLE permits one link between two devices, so a second one is at best wasted and
     * at worst the thing that displaces the first.
     */
    fun onDiscovered(
        address: String,
        now: Long,
        inboundAddresses: Set<String> = emptySet(),
        peerAddresses: Set<String> = emptySet()
    ): Decision {
        if (address in established) return Decision.Skip("already linked")
        if (address in pending) return Decision.Skip("attempt already in flight")
        if (address in inboundAddresses) return Decision.Skip("already connected to us as a central")

        val otherAddressesOfSamePeer = peerAddresses.filter { it != address }
        val linkedElsewhere = otherAddressesOfSamePeer.firstOrNull {
            it in established || it in inboundAddresses
        }
        if (linkedElsewhere != null) {
            return Decision.Skip("same peer already linked as ${linkedElsewhere.take(8)}")
        }

        if (pending.size >= maxConcurrentConnects) {
            return Decision.Skip("${pending.size} attempt(s) already in flight")
        }
        if (established.size >= maxCentralLinks) {
            return Decision.Skip("${established.size} outbound link(s) already open")
        }

        val since = lastActivity[address]
        if (since != null && now - since < backoffFor(attemptCount[address] ?: 0)) {
            return Decision.Skip("within backoff")
        }

        pending[address] = now
        lastActivity[address] = now
        attemptCount[address] = (attemptCount[address] ?: 0) + 1
        return Decision.Connect
    }

    /** The attempt to [address] produced a usable link. */
    fun onConnected(address: String) {
        pending.remove(address)
        established.add(address)
        attemptCount.remove(address)
        lastActivity.remove(address)
    }

    /** The attempt to [address] failed, or its link went down, at [now]. */
    fun onReleased(address: String, now: Long) {
        pending.remove(address)
        established.remove(address)
        if (attemptCount.containsKey(address)) lastActivity[address] = now
    }

    /**
     * gattlib refused a new attempt to [address] because it still owns an earlier one, at [now].
     *
     * This is the state the reaper leaves behind. It abandons an attempt that produced no callback,
     * but nothing on this side can cancel the native attempt: `gattlib_disconnect` needs a
     * connection pointer that only a successful connect produces, and it refuses a device still in
     * `CONNECTING` anyway. So the address stays gattlib's until gattlib lets go, and asking again
     * returns `GATTLIB_BUSY`.
     *
     * Two things follow. The connect slot is freed, because there is one initiator and holding it
     * for an address we cannot use stops the node reaching anybody else. And the address goes to the
     * longest backoff rather than the base one, because retrying sooner only collects another
     * refusal.
     */
    fun onNativeBusy(address: String, now: Long) {
        pending.remove(address)
        established.remove(address)
        attemptCount[address] = attemptsForMaxBackoff()
        lastActivity[address] = now
    }

    /** The attempt count at which [backoffFor] has saturated at [maxBackoffMs]. */
    private fun attemptsForMaxBackoff(): Int {
        var attempts = 1
        while (backoffFor(attempts) < maxBackoffMs && attempts < MAX_BACKOFF_ATTEMPT_CEILING) {
            attempts++
        }
        return attempts
    }

    /**
     * Attempts started long enough ago that no callback is coming. gattlib never fails these on its
     * own, so abandoning them here is what frees the initiator for the next peer.
     */
    fun expiredAttempts(now: Long): List<String> =
        pending.filterValues { now - it >= connectTimeoutMs }.keys.toList()

    fun isPending(address: String): Boolean = address in pending

    fun pendingCount(): Int = pending.size

    fun establishedCount(): Int = established.size

    fun clear() {
        pending.clear()
        established.clear()
        lastActivity.clear()
        attemptCount.clear()
    }

    /** Delay owed after [attempts] failures: [baseBackoffMs] doubling up to [maxBackoffMs]. */
    internal fun backoffFor(attempts: Int): Long {
        if (attempts <= 0) return 0L
        var backoff = baseBackoffMs
        repeat(attempts - 1) {
            if (backoff >= maxBackoffMs) return maxBackoffMs
            backoff *= 2
        }
        return if (backoff > maxBackoffMs) maxBackoffMs else backoff
    }

    companion object {
        /**
         * One. The controller has a single initiator, and a second request cancels the first — the
         * `le-connection-abort-by-local` in the journal.
         */
        const val MAX_CONCURRENT_CONNECTS = 1

        /**
         * How many outbound links to hold. The mesh wants several peers, but this radio is a
         * UART-attached Spreadtrum part whose budget is small and whose links died within seconds
         * whenever attempts overlapped, so the cap stays low until a healthy link is routine.
         */
        const val MAX_CENTRAL_LINKS = 2

        /**
         * How long an attempt may sit with no callback before it is abandoned.
         *
         * Successful connects in the journal took 8.2 s, 13.8 s and 22.0 s, and BlueZ's own
         * failures came back at 16-19 s, so the deadline has to clear 22 s to avoid cancelling
         * connects that were about to work. gattlib's `CONNECT_TIMEOUT_SEC` of 10 s is not a
         * deadline at all: its handler only clears the timer.
         */
        const val CONNECT_TIMEOUT_MS = 30_000L

        /** How often the deadline is checked. */
        const val SWEEP_INTERVAL_MS = 5_000L

        const val BASE_BACKOFF_MS = 5_000L
        const val MAX_BACKOFF_MS = 60_000L

        /** Stops [attemptsForMaxBackoff] looping if the backoff constants are ever made unreachable. */
        private const val MAX_BACKOFF_ATTEMPT_CEILING = 32
    }
}
