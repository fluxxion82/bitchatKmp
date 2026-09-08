package com.bitchat.bluetooth.manager

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

    private val peerToDevices = mutableMapOf<String, MutableSet<String>>()
    private val deviceToPeer = mutableMapOf<String, String>()

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
     * Record that [peerID] is reachable at [address].
     *
     * An address already bound to a different peer is re-pointed: BlueZ hands out an address to one
     * device at a time, so the previous owner has moved on.
     */
    fun bind(peerID: String, address: String): Binding {
        val previousOwner = deviceToPeer[address]
        val isNewLink = previousOwner != peerID

        if (previousOwner != null && previousOwner != peerID) {
            peerToDevices[previousOwner]?.let { devices ->
                devices.remove(address)
                if (devices.isEmpty()) peerToDevices.remove(previousOwner)
            }
        }

        deviceToPeer[address] = peerID
        val devices = peerToDevices.getOrPut(peerID) { mutableSetOf() }
        val superseded = if (isNewLink) devices.filter { it != address } else emptyList()
        devices.add(address)

        return Binding(isNewLink = isNewLink, superseded = superseded)
    }

    /**
     * Forget [address]. Called when its link goes down; the peer stays known through whatever other
     * address it is now using.
     */
    fun release(address: String) {
        val peerID = deviceToPeer.remove(address) ?: return
        val devices = peerToDevices[peerID] ?: return
        devices.remove(address)
        if (devices.isEmpty()) peerToDevices.remove(peerID)
    }

    fun peerFor(address: String): String? = deviceToPeer[address]

    fun addressesFor(peerID: String): Set<String> = peerToDevices[peerID]?.toSet() ?: emptySet()

    fun addressFor(peerID: String): String? = peerToDevices[peerID]?.firstOrNull()

    fun snapshot(): Map<String, String> = deviceToPeer.toMap()

    fun clear() {
        peerToDevices.clear()
        deviceToPeer.clear()
    }
}
