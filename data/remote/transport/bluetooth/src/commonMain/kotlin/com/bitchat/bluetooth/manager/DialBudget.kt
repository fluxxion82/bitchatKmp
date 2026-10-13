package com.bitchat.bluetooth.manager

/**
 * How much dial time a node may use, whatever the number of addresses it hears.
 *
 * A dial switches a board's advertising and scanning off for its length: about ten seconds when it
 * comes up, 25 to 34 when it does not. [CentralLinkPolicy] makes an address that does not answer
 * wait longer each time, but addresses cost a sender nothing and a phone takes a new one every
 * fifteen minutes, so no rule about one address bounds what many cost together: the boards were
 * measured spending between an eighth and more than a third of their time inside dials.
 *
 * So there is one allowance for all of them. It starts full, regains one part in [refillDivisor]
 * of the time that passes, and loses the time during which a dial is in flight. Whether a dial may
 * start depends on how much is left and on the address's [Standing], and the one standing that
 * matters most is the one nobody can give themselves: only an address whose link to this node has
 * just lasted may use the last of the allowance, for a few redials. Any other address may start a
 * dial only with more than [UNTRIED_RESERVE_MS] left, so as long as that dial lasts no more than
 * 36 s it cannot take all of what a peer whose link has just dropped needs to get it back,
 * however many such addresses are on the air.
 *
 * In the long run that is about one part in [refillDivisor] of the time, plus one allowance, as
 * long as nothing was discarded at the lower limit. The lower limit is there for recovery, not as
 * part of that sum: whatever happened, the node is at most one allowance in debt.
 *
 * The balance is kept in [refillDivisor]-ths of a millisecond, so that a millisecond that passes
 * adds a whole number and nothing is rounded away however often [settle] is called.
 *
 * Not thread-safe. The caller holds its own lock, as it does for the policy.
 */
class DialBudget(
    capacityMs: Long = CAPACITY_MS,
    private val refillDivisor: Long = REFILL_DIVISOR,
    untriedReserveMs: Long = UNTRIED_RESERVE_MS,
    failingReserveMs: Long = FAILING_RESERVE_MS
) {
    /**
     * What an address has shown so far, which decides how much must be left before it is dialled.
     * [CentralLinkPolicy] decides which one an address has.
     */
    enum class Standing {
        /**
         * It has redials left from a link to this node that lasted. It needs only that something
         * is left.
         */
        LINKED,

        /**
         * No redials left, and not dialled since it last linked, or ever. It needs more than is
         * kept for [LINKED].
         */
        UNTRIED,

        /**
         * No redials left, and dialled since it last linked, or ever. It needs more than is kept
         * for [UNTRIED] as well: retries of addresses that do not answer are most of the dial time.
         */
        FAILING
    }

    private val capacity = capacityMs * refillDivisor
    private val untriedReserve = untriedReserveMs * refillDivisor
    private val failingReserve = failingReserveMs * refillDivisor
    private var balance = capacity
    private var settledAt: Long? = null

    /**
     * Count the time since the last call, up to [now]: all of it regains its part, and it is
     * charged in full when a dial was in flight ([dialling]).
     *
     * A [now] that is not later than the last one changes nothing, and counting goes on from it.
     * A caller on a monotonic clock sees that only when it read the clock before it took its lock;
     * one on the time of day would see it when the clock is set back, and must not then go without
     * regaining anything until the old time comes round again.
     */
    fun settle(now: Long, dialling: Boolean) {
        val since = settledAt
        settledAt = now
        if (since == null || now <= since) return

        val elapsed = now - since
        balance += elapsed
        if (dialling) balance -= elapsed * refillDivisor
        balance = balance.coerceIn(-capacity, capacity)
    }

    /** Whether a dial to an address of that [standing] may start with what is left now. */
    fun allows(standing: Standing): Boolean = balance > when (standing) {
        Standing.LINKED -> 0L
        Standing.UNTRIED -> untriedReserve
        Standing.FAILING -> failingReserve
    }

    /** What is left, in whole milliseconds rounded down, so that any debt reads as debt. */
    fun balanceMs(): Long = balance.floorDiv(refillDivisor)

    companion object {
        /**
         * A redial and three retries of 25 s, as a board makes them to a peer whose link has just
         * dropped, fit into a full allowance without a wait.
         */
        const val CAPACITY_MS = 90_000L

        /**
         * A sixth. The hours measured with nothing unusual in the room had 12 to 21 % of the time
         * in dials, and a third of first dials and a fifth of retries made a link; the limit is to
         * cut the hours at 32 and 38 %, not to halve ordinary dialling.
         */
        const val REFILL_DIVISOR = 6L

        /**
         * What only an address in [Standing.LINKED] may use. A dial costs five sixths of its
         * length, so one that starts just above this and fails after 36 s still leaves something;
         * the longest seen was 34 s.
         */
        const val UNTRIED_RESERVE_MS = 30_000L

        /** What an address in [Standing.FAILING] may not use: the above, and as much again for first tries. */
        const val FAILING_RESERVE_MS = 60_000L
    }
}

/** The balance for the link status line: whole seconds rounded down, so any debt shows as debt. */
fun dialTimeLeftText(balanceMs: Long): String = "${balanceMs.floorDiv(1_000L)}s"
