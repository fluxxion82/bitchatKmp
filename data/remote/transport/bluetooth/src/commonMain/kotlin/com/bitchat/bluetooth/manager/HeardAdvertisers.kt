package com.bitchat.bluetooth.manager

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * The addresses BlueZ has recently heard advertise, in the order it first heard them: the only
 * addresses the embedded node dials.
 *
 * BlueZ makes a device object for anything it has to do with, also for the address a central
 * connected in from, and a phone connects from one private address while it advertises under
 * another. An address that only connected in is one nobody advertises: a dial to it can only time
 * out, and while a dial lasts (7 to 25 s) this controller neither advertises nor scans. Measured
 * on the boards on 2026-10-09: of 553 dials in two hours 239 went to such addresses, one of
 * which had connected in 267 times in twenty minutes, and a quarter of the time went into dials.
 *
 * What tells the two apart is the signal strength: BlueZ reports an RSSI for a device it hears
 * advertise, again about every ten seconds (median; under 14 s nine times in ten on a board that
 * is not busy dialling), and never for one that only connected in. So nothing has to be
 * remembered about a peer: an address is a candidate for as long as the reports keep coming, and
 * one that was dropped here by mistake is back at the next.
 *
 * Nothing is authenticated, anyone can advertise under any number of addresses. The table is
 * bounded, and an address that is still heard never gives way to a new one: with the table full
 * of those the newcomer is not recorded, and is at a later report once there is room. Whoever
 * floods the air with advertisers can keep a real newcomer out that way; it can also, and could
 * before this class existed, keep this node dialling its addresses one after another, which a
 * table cannot help. What bounds that is the limit on dialling as a whole, [DialBudget].
 *
 * Pure bookkeeping with no radio and no clock: callers pass `now` in milliseconds from a clock
 * that only goes forward. Reports arrive on the D-Bus dispatch thread while a coroutine reads the
 * recent addresses, hence the lock.
 */
class HeardAdvertisers(
    private val windowMs: Long = HEARD_WINDOW_MS,
    private val maxTracked: Int = MAX_TRACKED
) {

    private val lock = SynchronizedObject()
    private val heardAt = LinkedHashMap<String, Long>()

    /** BlueZ reported [address]'s signal strength at [now]. */
    fun onHeard(address: String, now: Long): Unit = synchronized(lock) {
        if (address in heardAt) {
            heardAt[address] = now
            return
        }
        if (heardAt.size >= maxTracked) {
            heardAt.entries.removeAll { now - it.value >= windowMs }
        }
        if (heardAt.size < maxTracked) heardAt[address] = now
    }

    /** BlueZ dropped [address]'s device, or a dial found it gone. */
    fun onRemoved(address: String): Unit = synchronized(lock) {
        heardAt.remove(address)
    }

    /** Whether BlueZ reported [address]'s signal strength within the window ending at [now]. */
    fun isHeard(address: String, now: Long): Boolean = synchronized(lock) {
        heardAt[address]?.let { now - it < windowMs } == true
    }

    /** Every address BlueZ has heard within the window ending at [now], in first-heard order. */
    fun recent(now: Long): List<String> = synchronized(lock) {
        heardAt.filterValues { now - it < windowMs }.keys.toList()
    }

    companion object {
        const val HEARD_WINDOW_MS = 60_000L
        const val MAX_TRACKED = 256
    }
}
