package com.bitchat.bluetooth.manager

/**
 * The addresses the embedded node's sweep may offer to [CentralLinkPolicy] again.
 *
 * BlueZ announces a device once, when its object appears, and gattlib passes that straight through,
 * so a peer whose connect failed gets a second try only because the sweep remembers it. A
 * remembered address is worth offering only while BlueZ still has the device, though: once
 * bluetoothd drops the object, gattlib frees its record with it and every dial fails at once with
 * `gattlib_connect: Cannot find connection`. Nothing about that can change until BlueZ sees the
 * peer again, and gattlib announces it when that happens, which is what puts the address back.
 *
 * Not thread-safe. The caller holds its own lock, as it does for the policy.
 */
class KnownPeers {

    // Address to the number of the sighting that last named it, in the order first named.
    private val sightings = LinkedHashMap<String, Long>()
    private var lastSighting = 0L

    /** The scanner, or BlueZ itself, named [address]. */
    fun onSighted(address: String) {
        lastSighting += 1
        sightings[address] = lastSighting
    }

    /**
     * The sighting a dial to [address] is about to act on, to be handed back to
     * [onUnknownToBlueZ]. Null when the address has never been named.
     */
    fun sightingOf(address: String): Long? = sightings[address]

    /**
     * A dial to [address] was refused because BlueZ has no such device.
     *
     * [sighting] is what [sightingOf] answered before the dial. The scanner runs on another thread,
     * so BlueZ can re-create the device and have it announced while the refusal is still on its way
     * here. That announcement is the only one there will be, so dropping the address after it
     * would leave a peer BlueZ does have with nothing to offer it again. Returns true when the
     * address was dropped.
     */
    fun onUnknownToBlueZ(address: String, sighting: Long?): Boolean {
        if (sighting == null || sightings[address] != sighting) return false
        sightings.remove(address)
        return true
    }

    /** The addresses to offer again: named, not since refused as unknown, and not in [held]. */
    fun candidates(held: Set<String>): Set<String> = sightings.keys - held
}
