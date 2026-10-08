package com.bitchat.bluetooth.manager

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Which links BlueZ holds that nothing in this app uses, and when to ask BlueZ to drop one.
 *
 * Measured on the boards on 2026-10-08, twice each: bluetoothd held a link to the other board while
 * the app broadcast to no devices, and nothing ended that state until the link was dropped by hand.
 * One way in is a dial that completes later than gattlib's D-Bus timeout: the app has given the
 * attempt up, the kernel connects a few seconds later, and gattlib cannot join a link whose services
 * BlueZ has already resolved (it waits for a change that has already happened). The other is an app
 * restart; those links are dropped before the app advertises (see [bitchatLinks]).
 *
 * A link is given the central policy's connect deadline before it is judged, because one that has
 * just come up can still become usable by itself: the other side subscribes and writes (seen 5 s and
 * 22 s after link-up), and a gattlib attempt the reaper has abandoned still gets its callback when
 * the services resolve. There is deliberately no exemption for an attempt in flight: an attempt on a
 * link that has been up for the whole deadline cannot succeed any more.
 *
 * Pure bookkeeping with no radio and no clock: callers pass `now` in milliseconds from a clock that
 * only goes forward (the boards have no battery clock and their time of day jumps when NTP lands).
 * Reports arrive on the D-Bus dispatch thread while a coroutine asks what is due, hence the lock.
 */
class OrphanedLinks(private val graceMs: Long = CentralLinkPolicy.CONNECT_TIMEOUT_MS) {

    /**
     * One link that was found due. It names the link, not only the address: if that link goes and
     * another to the same address comes up, the new one is a different link and this no longer
     * stands for anything.
     */
    class Orphan internal constructor(val address: String, internal val link: Long) {
        override fun toString(): String = "Orphan($address)"
    }

    private data class Entry(val link: Long, val upAt: Long, var askedAt: Long? = null)

    private val lock = SynchronizedObject()
    private val links = LinkedHashMap<String, Entry>()
    private var linksSeen = 0L

    /** BlueZ reports a link to [address]. A link already known keeps the time it first came up. */
    fun onLinkUp(address: String, now: Long): Unit = synchronized(lock) {
        if (address !in links) links[address] = Entry(link = ++linksSeen, upAt = now)
    }

    /** BlueZ reports the link gone, or dropped the device. Nothing is remembered about it. */
    fun onLinkGone(address: String): Unit = synchronized(lock) {
        links.remove(address)
    }

    /**
     * The links to ask BlueZ to drop at [now]: up for the whole grace, not asked for within the
     * last grace, and not [inUse]. Each one returned is recorded as asked for at [now]; it stays
     * known until [onLinkGone], so a request BlueZ did not act on is made again a grace later.
     *
     * [inUse] is asked only about a link that is otherwise due, and after the caller has read
     * [now]. That order is the point: a central that wrote before its deadline has been registered
     * by the time the question is put, so it is always found in use. A use that begins after the
     * question is not seen, and that link is dropped.
     *
     * [inUse] is called with the lock held and must not block.
     */
    fun due(now: Long, inUse: (String) -> Boolean): List<Orphan> = synchronized(lock) {
        links.mapNotNull { (address, entry) ->
            if (!isDue(address, entry, now, inUse)) return@mapNotNull null
            entry.askedAt = now
            Orphan(address, entry.link)
        }
    }

    /**
     * Whether [due] would return anything at [now], without recording that anything was asked for.
     * For deciding whether the bus is worth opening: what is then done is decided by [due], later.
     */
    fun anyDue(now: Long, inUse: (String) -> Boolean): Boolean = synchronized(lock) {
        links.any { (address, entry) -> isDue(address, entry, now, inUse) }
    }

    private fun isDue(address: String, entry: Entry, now: Long, inUse: (String) -> Boolean): Boolean {
        if (now - entry.upAt < graceMs) return false
        if (entry.askedAt?.let { now - it < graceMs } == true) return false
        return !inUse(address)
    }

    /**
     * Whether [orphan] is still the link BlueZ holds to that address. To be asked immediately
     * before the request is sent: between finding a link due and sending, the bus is waited for,
     * and a link that went and came back in that time is a new one that has had no grace yet.
     */
    fun isSameLink(orphan: Orphan): Boolean = synchronized(lock) {
        links[orphan.address]?.link == orphan.link
    }

    /**
     * Stop judging [orphan]'s link: BlueZ shows it is not a bitchat peer's, so it is not this
     * app's to drop. A later link to the same address is judged afresh.
     */
    fun leaveAlone(orphan: Orphan): Unit = synchronized(lock) {
        if (links[orphan.address]?.link == orphan.link) links.remove(orphan.address)
    }

    fun tracked(): Set<String> = synchronized(lock) { links.keys.toSet() }
}

/** What BlueZ says about one device: its `Device1` `Connected` and `UUIDs` properties. */
data class BlueZDeviceInfo(val address: String, val connected: Boolean, val uuids: List<String>)

/**
 * The addresses BlueZ holds a link to that are a bitchat peer's: connected, and showing the bitchat
 * service among their UUIDs (from the advertisement, or from the services BlueZ resolved).
 *
 * This is the test for "ours to disconnect", used both for the links bluetoothd kept from a
 * previous process of the app and for a link nothing here uses. A connected device that does not
 * show the service is left alone: nothing says it is ours, and it may be one another program
 * connected.
 */
fun bitchatLinks(devices: List<BlueZDeviceInfo>, serviceUuid: String): List<String> =
    devices.filter { device ->
        device.connected && device.uuids.any { uuid -> uuid.equals(serviceUuid, ignoreCase = true) }
    }.map { it.address }
