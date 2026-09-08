package com.bitchat.bluetooth.manager

import kotlin.concurrent.Volatile

/**
 * Which centrals currently hold a link to our GATT server.
 *
 * Peripheral-role bookkeeping, kept out of the platform services so the two rules that matter can
 * be tested without a radio: a link that goes down is dropped, and a send aimed at an address we
 * do not hold is a failure rather than a silent success.
 *
 * Both rules were missing on Linux. The registry was append-only, so an Android phone rotating its
 * resolvable private address accumulated several entries, and notifications were emitted into
 * links that had been down for a minute while every send reported success.
 *
 * The set is held copy-on-write behind a volatile reference. Writes come from the D-Bus dispatch
 * thread (a `WriteValue` arriving, a `Device1` signal) while reads come from the coroutine that is
 * broadcasting, so a plain mutable set would let a broadcast iterate a set being pruned underneath
 * it. A reader always sees one whole generation, never a half-built one.
 */
class GattClientRegistry {

    @Volatile
    private var clients: Set<String> = emptySet()

    /** @return true when [address] was not already registered, i.e. this is a new link. */
    fun onConnected(address: String): Boolean {
        val current = clients
        if (address in current) return false
        clients = current + address
        return true
    }

    /** @return true when [address] was registered and has now been dropped. */
    fun onDisconnected(address: String): Boolean {
        val current = clients
        if (address !in current) return false
        clients = current - address
        return true
    }

    fun isConnected(address: String): Boolean = address in clients

    fun addresses(): Set<String> = clients

    /**
     * Split the addresses a peripheral-role broadcast is aimed at into the links we still hold and
     * the entries that are stale.
     *
     * The peripheral send itself cannot report this. A BlueZ notification is a `PropertiesChanged`
     * signal on a device-agnostic object path, so it succeeds whether or not anybody is listening;
     * the registry is the only place that knows a target is gone. Without this, a broadcast into
     * nothing but dead entries looked exactly like a delivered one.
     */
    fun partitionTargets(addresses: List<String>): BroadcastTargets {
        val current = clients
        val (live, stale) = addresses.partition { it in current }
        return BroadcastTargets(live = live, stale = stale)
    }

    fun size(): Int = clients.size

    fun isEmpty(): Boolean = clients.isEmpty()

    fun clear() {
        clients = emptySet()
    }
}

/**
 * Broadcast targets checked against the links actually held.
 *
 * @property live addresses a notification will reach.
 * @property stale addresses that name a link that is gone; they should be dropped, not delivered to.
 */
data class BroadcastTargets(
    val live: List<String>,
    val stale: List<String>
) {
    /** False when every target was stale, i.e. the send would report success while going nowhere. */
    val deliverable: Boolean get() = live.isNotEmpty()
}
