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
 * So this class enforces four things, and owns the deadline gattlib does not:
 *
 *  - at most [maxConcurrentConnects] attempts in flight, so the initiator is never contended;
 *  - at most [maxCentralLinks] outbound links, so the controller's connection budget is not spent
 *    on stale addresses of one phone;
 *  - an attempt that has produced no callback within [connectTimeoutMs] is abandoned, which is the
 *    only thing that ever ends one;
 *  - when a [dialBudget] is given, no more dial time in all than it allows, whatever the number of
 *    addresses on offer. See [DialBudget] for why and how much.
 *
 * What the budget is charged is the time this class has an attempt in flight, read from its own
 * record at the start of every call that is given the time and can change that record. How much
 * must be left for a dial depends on the address's [DialBudget.Standing], and the best one is
 * earned in one way only: a link this node dialled that lasted [healthyLinkMs] gives its address,
 * when it ends, [REDIALS_AFTER_A_LINK] dials for which anything left is enough. Each dial granted
 * uses one up, however it ends, and only the [MAX_REDIAL_ADDRESSES] addresses whose links ended
 * last have any. Every other address needs more than a first reserve for its first dial and more
 * than a second for every one after. Nothing else a peer says or does gives its address such
 * dials or renews the ones it has: not a frame it sends, not connecting in, not a link that is gone
 * again too soon, and least of all being new. A refusal for want of dial time is the last check and
 * changes nothing: no attempt is counted, no backoff starts and no redial is used.
 *
 * It is pure bookkeeping with no radio and no clock, so the rules can be tested on the JVM; the
 * linuxArm64 target they run on is cross-compiled and cannot run its own tests. Callers pass `now`
 * in milliseconds on one clock of their choosing, and only differences are used. The boards pass a
 * monotonic one: theirs have no battery, and the time of day jumps forward by hours when NTP lands.
 */
class CentralLinkPolicy(
    private val maxConcurrentConnects: Int = MAX_CONCURRENT_CONNECTS,
    private val maxCentralLinks: Int = MAX_CENTRAL_LINKS,
    private val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
    private val baseBackoffMs: Long = BASE_BACKOFF_MS,
    private val maxBackoffMs: Long = MAX_BACKOFF_MS,
    private val immediateDropRetryEnabled: Boolean = false,
    private val healthyLinkMs: Long = CONNECT_TIMEOUT_MS,
    private val dialBudget: DialBudget? = null
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

    // The embedded radio sometimes repeatedly succeeds at connecting only to lose the link before
    // carrying traffic. Keep that failure mode separate from ordinary failed attempts so the
    // desktop policy retains its established backoff semantics unless it opts in.
    private val connectedAt = mutableMapOf<String, Long>()
    private val immediateDropCount = mutableMapOf<String, Int>()
    private val lastImmediateDrop = mutableMapOf<String, Long>()

    // How many dials at the best standing an address has left, in the order the links that earned
    // them ended; see [standingOf]. Written only where a link that lasted ends and where one of
    // those dials is granted.
    private val redialsLeft = LinkedHashMap<String, Int>()

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
        settleDialTime(now)
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

        val lastDrop = lastImmediateDrop[address]
        if (immediateDropRetryEnabled && lastDrop != null &&
            now - lastDrop < immediateDropBackoffFor(immediateDropCount[address] ?: 0)
        ) {
            return Decision.Skip("within immediate-drop backoff")
        }

        val since = lastActivity[address]
        if (since != null && now - since < backoffFor(attemptCount[address] ?: 0)) {
            return Decision.Skip("within backoff")
        }

        if (dialBudget != null) {
            val standing = standingOf(address)
            if (!dialBudget.allows(standing)) {
                return Decision.Skip(
                    if (dialBudget.allows(DialBudget.Standing.LINKED)) {
                        "dial time kept for peers in better standing"
                    } else {
                        "dial time used up"
                    }
                )
            }
            if (standing == DialBudget.Standing.LINKED) useRedial(address)
        }

        pending[address] = now
        lastActivity[address] = now
        attemptCount[address] = (attemptCount[address] ?: 0) + 1
        return Decision.Connect
    }

    /** The attempt to [address] produced a usable link. */
    fun onConnected(address: String, now: Long = UNSET_TIME) {
        // A caller that forgets the time would make every link look healthy and silently switch
        // the quarantine off, which is the bug this policy exists to prevent, and would leave the
        // dial budget counting from nowhere. Fail loudly instead.
        val timed = immediateDropRetryEnabled || dialBudget != null
        if (timed) require(now != UNSET_TIME) { "the time the link came up is needed" }
        settleDialTime(now)
        pending.remove(address)
        established.add(address)
        attemptCount.remove(address)
        lastActivity.remove(address)
        if (timed) connectedAt[address] = now
    }

    /** The attempt to [address] failed, or its link went down, at [now]. */
    fun onReleased(address: String, now: Long) {
        settleDialTime(now)
        pending.remove(address)
        val connectedSince = connectedAt.remove(address)
        established.remove(address)
        // A time here means a link this node dialled is ending, and that the caller keeps times at
        // all, which it does for the budget or for the immediate-drop ladder.
        if (connectedSince != null && now - connectedSince >= healthyLinkMs) grantRedials(address)
        if (immediateDropRetryEnabled && connectedSince != null) {
            if (now - connectedSince >= healthyLinkMs) {
                clearImmediateDropRecord(address)
            } else {
                immediateDropCount[address] = (immediateDropCount[address] ?: 0) + 1
                lastImmediateDrop[address] = now
            }
            return
        }
        if (attemptCount.containsKey(address)) lastActivity[address] = now
    }

    /**
     * Release every established address that is not in [held], the addresses the GATT client holds
     * a connection for, and return them.
     *
     * This record and the client's are kept by different threads, and a link's loss can be handled
     * before its ready. Measured on a board: an address stayed established here for hours after the
     * client had dropped it, which with the one real link filled [maxCentralLinks], and the board
     * dialled nobody. An attempt in flight is not touched: the client holds nothing for an address
     * until gattlib reports the connection.
     */
    fun releaseUnheld(held: Set<String>, now: Long): List<String> {
        val released = established.filter { it !in held }
        released.forEach { onReleased(it, now) }
        return released
    }

    /** A mesh frame proves the link was useful, so prior immediate drops no longer predict it. */
    fun onMeshFrameExchanged(address: String) {
        if (immediateDropRetryEnabled) clearImmediateDropRecord(address)
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
     * for an address we cannot use stops the node reaching anybody else. And the address waits
     * about a minute rather than the base backoff, because retrying sooner only collects another
     * refusal. A minute whatever [maxBackoffMs] is: the refusal is about gattlib's hold on a dead
     * attempt, not about the peer, so it is no reason to go to a ceiling that exists for peers that
     * never answer. Nor does it shorten a longer wait the address has already earned.
     */
    fun onNativeBusy(address: String, now: Long) {
        settleDialTime(now)
        pending.remove(address)
        established.remove(address)
        connectedAt.remove(address)
        attemptCount[address] = maxOf(attemptCount[address] ?: 0, attemptsForNativeBusyBackoff())
        lastActivity[address] = now
    }

    /**
     * The attempt count at which [backoffFor] first reaches [NATIVE_BUSY_BACKOFF_MS]. Under a
     * [maxBackoffMs] shorter than that it never does, and the count stops at the ceiling, where
     * the backoff is [maxBackoffMs] all the same.
     */
    private fun attemptsForNativeBusyBackoff(): Int {
        var attempts = 1
        while (backoffFor(attempts) < NATIVE_BUSY_BACKOFF_MS && attempts < MAX_BACKOFF_ATTEMPT_CEILING) {
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

    /** The dial time left at [now], in milliseconds and negative when overdrawn; null with no budget. */
    fun dialTimeLeftMs(now: Long): Long? = dialBudget?.let {
        settleDialTime(now)
        it.balanceMs()
    }

    fun clear() {
        pending.clear()
        established.clear()
        lastActivity.clear()
        attemptCount.clear()
        connectedAt.clear()
        redialsLeft.clear()
        immediateDropCount.clear()
        lastImmediateDrop.clear()
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

    private fun immediateDropBackoffFor(drops: Int): Long = when (drops) {
        1 -> FIRST_IMMEDIATE_DROP_BACKOFF_MS
        2 -> SECOND_IMMEDIATE_DROP_BACKOFF_MS
        3 -> THIRD_IMMEDIATE_DROP_BACKOFF_MS
        else -> IMMEDIATE_DROP_QUARANTINE_MS
    }

    private fun clearImmediateDropRecord(address: String) {
        immediateDropCount.remove(address)
        lastImmediateDrop.remove(address)
    }

    // The peer whose link has just ended is the one the last of the dial time is kept for, so it
    // goes to the end of the line of those to be forgotten for want of room.
    private fun grantRedials(address: String) {
        redialsLeft.remove(address)
        redialsLeft[address] = REDIALS_AFTER_A_LINK
        if (redialsLeft.size > MAX_REDIAL_ADDRESSES) redialsLeft.remove(redialsLeft.keys.first())
    }

    private fun useRedial(address: String) {
        val left = redialsLeft[address] ?: return
        if (left <= 1) redialsLeft.remove(address) else redialsLeft[address] = left - 1
    }

    /**
     * What [address] has shown, for [DialBudget]. An address is free to make and free to copy, so
     * the standing that may use the last of the dial time is not a fact about an address that
     * lasts: it is a handful of dials, given when a link that lasted ends and used up one by one.
     * Whoever only repeats on the air an address that has linked gets what is left of that
     * handful, and whoever answers a dial without keeping the link gets nothing.
     */
    private fun standingOf(address: String): DialBudget.Standing = when {
        address in redialsLeft -> DialBudget.Standing.LINKED
        (attemptCount[address] ?: 0) > 0 -> DialBudget.Standing.FAILING
        else -> DialBudget.Standing.UNTRIED
    }

    // Before the caller changes [pending]: the time up to now belongs to what was in flight until now.
    private fun settleDialTime(now: Long) {
        dialBudget?.settle(now, dialling = pending.isNotEmpty())
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

        /** No time supplied. Only legal while [immediateDropRetryEnabled] is false. */
        const val UNSET_TIME = Long.MIN_VALUE

        const val BASE_BACKOFF_MS = 5_000L
        const val MAX_BACKOFF_MS = 60_000L

        /** What a BUSY answer from gattlib is worth at least; see [onNativeBusy]. */
        const val NATIVE_BUSY_BACKOFF_MS = 60_000L

        /**
         * On the boards a dial switches advertising and scanning off for its length (7 to 25 s
         * measured), so an address that never answers is tried less and less, up to once in ten
         * minutes; a peer that can be reached dials this node itself. Only a dial of this node's
         * own that succeeds starts the backoff again: a peer connecting in does not, because
         * nothing authenticates that, and whoever could fake it could have every dial it likes.
         */
        const val EMBEDDED_MAX_BACKOFF_MS = 600_000L

        /**
         * How many dials at the best standing a link that lasted gives its address when it ends.
         * On the boards a dial to an address that had failed up to four times since its last link
         * still came up one time in five; none of the 18 that followed five failures did.
         */
        const val REDIALS_AFTER_A_LINK = 5

        /**
         * How many addresses can have such dials left at one time: more than a board has peers at
         * a time, and a phone's address lasts a quarter of an hour. When another link that lasted
         * ends, the address whose own ended longest ago loses what it had left.
         */
        const val MAX_REDIAL_ADDRESSES = 8

        /**
         * A mesh peer can move briefly out of range, so give three immediate drops short retries
         * before pausing it. The two-minute pause stops a pairing prompt storm without turning one
         * bad moment into a long mesh blackout.
         */
        const val FIRST_IMMEDIATE_DROP_BACKOFF_MS = 5_000L
        const val SECOND_IMMEDIATE_DROP_BACKOFF_MS = 10_000L
        const val THIRD_IMMEDIATE_DROP_BACKOFF_MS = 20_000L
        const val IMMEDIATE_DROP_QUARANTINE_MS = 120_000L

        /** Stops [attemptsForNativeBusyBackoff] looping where the backoff cannot reach what it looks for. */
        private const val MAX_BACKOFF_ATTEMPT_CEILING = 32
    }
}
