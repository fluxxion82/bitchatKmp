package com.bitchat.bluetooth.manager

/**
 * Peers this node owes a handshake to: one could not be sent, or is being attempted, and it is
 * started again when a link to the peer comes up rather than on a timer.
 *
 * Two kinds are kept apart. A handshake the USER asked for (a private message was sent to that
 * peer) and one this node started by itself (a recovery after unreadable packets, a retry by the
 * sweeper). The second kind is triggered by packets nothing has authenticated, so it has its own
 * room: however many of those there are, they push out only one another, never a peer the user
 * asked for. Each kind keeps at most [capacity] peers and its oldest entry goes first.
 *
 * An action of the user's pays for one opening out of what belongs to the user. When that opening
 * could not leave over the radio, the user's entry says it is still to come, so that it can go out
 * once, later, and no received packet can make a second one go.
 *
 * Pure bookkeeping; callers serialise access.
 */
class OwedHandshakes(private val capacity: Int = HandshakeSupervisor.MAX_OWED_HANDSHAKES) {

    // For each peer the user asked for: whether an opening of the user's own is still to come.
    private val forUser = linkedMapOf<String, Boolean>()
    private val automatic = linkedSetOf<String>()

    val userCount: Int get() = forUser.size
    val automaticCount: Int get() = automatic.size

    operator fun contains(peerID: String): Boolean = peerID in forUser || peerID in automatic

    fun isForUser(peerID: String): Boolean = peerID in forUser

    /**
     * The user asked for a session with [peerID]. An automatic entry for it becomes the user's.
     *
     * [openingToCome] says that the opening an action of the user's pays for has not left: it is
     * noted, and stays noted until it is taken. False leaves what is noted as it is. An entry
     * already present keeps its place in the queue.
     */
    fun rememberForUser(peerID: String, openingToCome: Boolean = false) {
        automatic.remove(peerID)
        val noted = forUser[peerID]
        if (noted == null && forUser.size >= capacity) forUser.keys.firstOrNull()?.let(forUser::remove)
        forUser[peerID] = openingToCome || noted == true
    }

    /**
     * The peers an opening of the user's is still to come for and that are [ready] for it now, the
     * one asked for longest ago first. It is to come for none of them afterwards: whoever takes
     * one sends it, or says with [rememberForUser] that it did not leave.
     */
    fun takeOpeningsToCome(ready: (String) -> Boolean): List<String> {
        val taken = forUser.filter { (peerID, toCome) -> toCome && ready(peerID) }.keys.toList()
        taken.forEach { forUser[it] = false }
        return taken
    }

    /** This node started a handshake with [peerID] by itself. Never downgrades a user's entry. */
    fun rememberAutomatic(peerID: String) {
        if (peerID in forUser) return
        if (peerID in automatic) return
        if (automatic.size >= capacity) automatic.firstOrNull()?.let(automatic::remove)
        automatic.add(peerID)
    }

    fun remove(peerID: String) {
        forUser.remove(peerID)
        automatic.remove(peerID)
    }
}
