package com.bitchat.bluetooth.manager

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Which centrals that write to our GATT server have not subscribed to our characteristic, and when
 * to ask one to leave.
 *
 * A central hears us only through notifications it has subscribed to. One that connects afresh
 * subscribes within a couple of seconds of connecting. One that has not is deaf to us however long
 * it stays, and it does stay: it can still write, so from its side the link works.
 *
 * A central is known by its writes, not by being connected: on Android the server is also told of
 * every link this device's own client made, and the other end of such a link is a peripheral that
 * owes no subscription. (Measured the same day: counted from the connection, the phone's own
 * working link to another phone was dropped after the grace.)
 *
 * Measured on an Android phone on 2026-10-08: the app was restarted while three centrals were
 * connected. Android kept the links and handed them to the new process, whose GATT service is a
 * new one; the centrals lose their subscription when the service changes and none of the clients
 * in use (this app's or upstream's) subscribes again. The phone then received everything and
 * nothing it sent was acted on, for two hours, until its Bluetooth was switched off and on.
 * Dropping the link is what makes such a central connect and subscribe again.
 *
 * A connection is named once, after [graceMs] without a subscription. Its address is then not
 * named again for [askAgainAfterMs], even if it comes back and still does not subscribe: a client
 * that never subscribes must not be turned into a loop of connections. That time runs from the
 * moment the link is dropped ([takeIfStillDue]), not from the look that named it.
 *
 * What is reported comes through a [Service], one per GATT service set up. A platform callback can
 * still be on its way when its service has been taken down and the next one set up; what it
 * reports then is about a service that no longer exists, and changes nothing.
 *
 * Nothing here is authenticated: an address is whatever a central presents. So neither table ever
 * gives up a record to make room for another, because a record lost is a wrong answer later (a
 * central that subscribed taken for one that did not; a central asked a minute ago asked again).
 * A table that is full stops instead, which only ever leaves a central where every central was
 * before this class existed:
 * - once a connection could not be recorded, what is known here is no longer everything (the one
 *   left out may have subscribed), so until the service is reset nobody is named, and every
 *   central is taken for one that may have subscribed ([mayHaveSubscribed]);
 * - a central is only named while there is room to remember that it was, so at most [maxTracked]
 *   are asked in any [askAgainAfterMs]; who was asked is forgotten only once that time has passed.
 * [refusing] says which of the two is the case, for the log.
 * A record whose disconnection is never reported stays. If it never subscribed it is named and
 * taken out when its link is dropped ([takeIfStillDue]); if it had, it holds its place until the
 * service is reset.
 *
 * What that costs, knowingly: a peer in range that brings [maxTracked] connections past the grace
 * within one [askAgainAfterMs] uses up the asking for that time, and that many subscribed records
 * left behind by unreported disconnections stop the asking until the service is reset. In both
 * cases a leftover central stays deaf, as every one did before; the same peer could hold every
 * connection the phone has anyway.
 *
 * Pure bookkeeping with no radio and no clock: callers pass `now` in milliseconds from a clock that
 * only goes forward. The platform's callbacks and the loop that asks what is due run on different
 * threads, hence the lock.
 */
class UnsubscribedCentrals(
    private val graceMs: Long = SUBSCRIBE_GRACE_MS,
    private val askAgainAfterMs: Long = ASK_AGAIN_AFTER_MS,
    private val maxTracked: Int = MAX_TRACKED
) {
    /**
     * One connection that was found due. It names the connection, not only the address: if that
     * connection ends and another from the same address begins, this no longer stands for anything.
     */
    class Due internal constructor(val address: String, internal val connection: Long) {
        override fun toString(): String = "Due($address)"
    }

    private class Central(val connection: Long, var seenAt: Long?, var subscribed: Boolean)

    private val lock = SynchronizedObject()
    private val connected = LinkedHashMap<String, Central>()
    private var connections = 0L

    /** Set when a connection could not be recorded; see the class. Cleared by [onServiceReset]. */
    private var full = false

    /** Whether the last look left a due connection unnamed for want of room to remember it. */
    private var askingRefused = false

    /** What is refused for want of room; both false in the ordinary case. See the class. */
    data class Refusing(val tracking: Boolean, val asking: Boolean)

    /** When each address was last asked to leave. Outlives the connection, which is the point. */
    private val asked = LinkedHashMap<String, Long>()

    /** Which [Service] is listened to: the one the last [onServiceReset] handed out. */
    private var serviceNumber = 0L

    /** What one GATT service reports; see the class for why it is not the tracker itself. */
    inner class Service internal constructor(private val number: Long) {

        /** The central has written to us: its grace runs from the first write on this connection. */
        fun onSeen(address: String, now: Long) = synchronized(lock) {
            if (number == serviceNumber) {
                val central = connected[address]
                if (central == null) {
                    record(address, seenAt = now, subscribed = false)
                } else if (central.seenAt == null) {
                    central.seenAt = now
                }
            }
        }

        /** The central enabled notifications on this connection. */
        fun onSubscribed(address: String) = synchronized(lock) {
            if (number == serviceNumber) {
                val central = connected[address]
                if (central == null) {
                    record(address, seenAt = null, subscribed = true)
                } else {
                    central.subscribed = true
                }
            }
        }

        /** The connection ended, and its subscription with it. */
        fun onGone(address: String) = synchronized(lock) {
            if (number == serviceNumber) {
                connected.remove(address)
            }
        }
    }

    /**
     * The GATT service is being taken down or set up anew: every subscription was to the old one,
     * so nothing known about who is connected or subscribed holds any more, and nothing the old
     * one still reports is taken. Who was asked to leave is kept, so that a stop and a start do
     * not turn into a second round of asking.
     */
    fun onServiceReset(): Service = synchronized(lock) {
        connected.clear()
        full = false
        Service(++serviceNumber)
    }

    /**
     * The connections to drop now. Each is recorded as asked, so the next call does not name it
     * again.
     */
    fun due(now: Long): List<Due> = synchronized(lock) {
        asked.entries.removeAll { now - it.value >= askAgainAfterMs }
        askingRefused = false

        val due = ArrayList<Due>()
        if (full) return@synchronized due
        for ((address, central) in connected) {
            val seenAt = central.seenAt ?: continue
            if (central.subscribed || now - seenAt < graceMs || address in asked) continue
            if (asked.size >= maxTracked) {
                askingRefused = true
                break
            }
            asked[address] = now
            due += Due(address, central.connection)
        }
        due
    }

    /**
     * Whether [due] still stands: the same connection, still without a subscription, and the
     * table not full since (nothing new is begun once what is known is no longer everything).
     * Asked before anything is claimed for the drop, because [due] answered a while ago; a
     * subscription that arrived since must win, and so must a new connection from the same address.
     */
    fun isStillDue(due: Due): Boolean = synchronized(lock) {
        val central = connected[due.address]
        !full && central != null && central.connection == due.connection && !central.subscribed
    }

    /**
     * The last look, and the record with it: true when [due] still stands, in which case the
     * connection is no longer recorded, because the caller is about to drop its link, and the time
     * before its address may be asked again runs from [now]. If the drop works the central is back
     * as a new connection; if the link was already gone and nobody said so, the record does not
     * stay behind; if the central is somehow still there, its next write records it anew and it is
     * asked again when its address may be.
     */
    fun takeIfStillDue(due: Due, now: Long): Boolean = synchronized(lock) {
        val central = connected[due.address]
        val stands = central != null && central.connection == due.connection && !central.subscribed
        // A link is dropped only under an asking that is still remembered, so this never adds to
        // the table. (With a clock that goes forward it always is: a look that forgets the asking
        // of a connection still due names it again in the same step.)
        if (stands && due.address in asked) {
            asked[due.address] = now
            connected.remove(due.address)
            true
        } else {
            false
        }
    }

    /**
     * Whether the central at [address] has, or may have, subscribed on its present connection to
     * the service in use: it is recorded as subscribed, or the table has been full, in which case
     * a subscription may have gone unrecorded. Asked before anything that would end a link of a
     * connection that was not found due.
     */
    fun mayHaveSubscribed(address: String): Boolean = synchronized(lock) {
        full || connected[address]?.subscribed == true
    }

    /** What is being refused for want of room right now. */
    fun refusing(): Refusing = synchronized(lock) { Refusing(tracking = full, asking = askingRefused) }

    /** How many records are kept, for tests of the bounds. */
    internal fun remembered(): Int = synchronized(lock) { connected.size + asked.size }

    private fun record(address: String, seenAt: Long?, subscribed: Boolean) {
        if (connected.size >= maxTracked) {
            full = true
            return
        }
        connected[address] = Central(++connections, seenAt, subscribed)
    }

    companion object {
        /** A fresh central subscribes one to three seconds after it connects (measured). */
        const val SUBSCRIBE_GRACE_MS = 15_000L
        const val ASK_AGAIN_AFTER_MS = 5 * 60_000L
        const val MAX_TRACKED = 64
    }
}
