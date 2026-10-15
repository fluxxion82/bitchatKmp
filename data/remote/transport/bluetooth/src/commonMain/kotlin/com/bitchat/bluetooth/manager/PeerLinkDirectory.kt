package com.bitchat.bluetooth.manager

/** The hop count this app gives a packet it makes for the Bluetooth mesh. */
const val MADE_HERE_TTL: UByte = 3u

/** The hop count the upstream clients give a packet they make. */
const val MADE_UPSTREAM_TTL: UByte = 7u

/**
 * Whether a packet that arrived with [ttl] may bind its link to the device that made it: its hop count
 * is one a maker gives its packets, which a relay takes down. Not a proof: an upstream packet reaches 3
 * after four relays, or fewer where an upstream relay lowers the count by more than one; and a
 * neighbour's own packet with another count (an upstream sync request has 0) binds nothing.
 */
fun mayBindItsMaker(ttl: UByte): Boolean = ttl == MADE_HERE_TTL || ttl == MADE_UPSTREAM_TTL

/**
 * Which BLE addresses currently stand for which peer.
 *
 * A peer is identified by its bitchat peer ID, not by the address it happens to be using. Android
 * centrals advertise under a resolvable private address and rotate it, so one phone reaches this
 * node as a stream of unrelated-looking MACs: over a twelve minute window the device journal shows
 * twelve distinct addresses for the single peer `269e37bb6be7caf9`.
 *
 * Keeping the mapping in one place makes the rotation visible. When a peer turns up on a new
 * address the addresses it used before are superseded: BLE allows only one link between two
 * devices, so the old ones name links that are already gone or are duplicates of the new one, and
 * holding them open costs the radio a connection slot it does not have. [bind] reports them so the
 * caller can release them.
 *
 * This is pure bookkeeping with no radio in it so the rotation rules can be tested on the JVM; the
 * linuxArm64 target where the problem shows up is cross-compiled and cannot run its own tests.
 * Callers serialise access; nothing here is thread safe on its own.
 */
class PeerLinkDirectory {

    private class Link(val peerID: String, var lastSeen: Long)

    private val peerToDevices = mutableMapOf<String, MutableSet<String>>()
    private val links = mutableMapOf<String, Link>()

    companion object {
        /**
         * How recently an address must have carried a packet to count as a live link.
         *
         * Rotation and a second live link look alike from the peer ID alone, so liveness is what
         * separates them: a rotated-away address goes silent immediately, a live link keeps carrying
         * packets.
         */
        const val LIVE_LINK_WINDOW_MS = 30_000L
    }

    /**
     * @property isNewLink true when [address] was not already bound to this peer, i.e. this is a
     *   link we have not seen the peer on before.
     * @property superseded the peer's other addresses, which this binding replaces.
     */
    data class Binding(
        val isNewLink: Boolean,
        val superseded: List<String>
    )

    /**
     * Record that [peerID] is reachable at [address], as of [now].
     *
     * An address already bound to a different peer is re-pointed: BlueZ hands out an address to one
     * device at a time, so the previous owner has moved on.
     *
     * Bind only from a packet that [mayBindItsMaker]: a packet names the peer that MADE it, so a
     * relayed one would make its maker look as though it moved onto the relay's link, and its real
     * link would be released. Only addresses that have gone quiet are reported as superseded: a phone
     * can hold two live links to this device at once, the one it connected in from and the one this
     * device dialled.
     */
    fun bind(peerID: String, address: String, now: Long): Binding {
        val previousOwner = links[address]?.peerID
        val isNewLink = previousOwner != peerID

        if (previousOwner != null && previousOwner != peerID) {
            peerToDevices[previousOwner]?.let { devices ->
                devices.remove(address)
                if (devices.isEmpty()) peerToDevices.remove(previousOwner)
            }
        }

        links[address] = Link(peerID, now)
        val devices = peerToDevices.getOrPut(peerID) { mutableSetOf() }
        val superseded = if (isNewLink) {
            // An address with no recorded sighting has never carried a packet, so it counts as quiet.
            devices.filter { it != address && now - (links[it]?.lastSeen ?: (now - LIVE_LINK_WINDOW_MS - 1)) > LIVE_LINK_WINDOW_MS }
        } else {
            emptyList()
        }
        devices.add(address)

        return Binding(isNewLink = isNewLink, superseded = superseded)
    }

    /**
     * A packet [bind] is not told about arrived on [address] at [now]. The link carries traffic, so
     * it is live, whoever is bound to it.
     */
    fun heard(address: String, now: Long) {
        links[address]?.lastSeen = now
    }

    /**
     * Forget [address]. Called when its link goes down; the peer stays known through whatever other
     * address it is now using.
     */
    fun release(address: String) {
        val peerID = links.remove(address)?.peerID ?: return
        val devices = peerToDevices[peerID] ?: return
        devices.remove(address)
        if (devices.isEmpty()) peerToDevices.remove(peerID)
    }

    fun peerFor(address: String): String? = links[address]?.peerID

    fun addressesFor(peerID: String): Set<String> = peerToDevices[peerID]?.toSet() ?: emptySet()

    fun addressFor(peerID: String): String? = peerToDevices[peerID]?.firstOrNull()

    fun snapshot(): Map<String, String> = links.mapValues { it.value.peerID }

    fun clear() {
        peerToDevices.clear()
        links.clear()
    }
}
