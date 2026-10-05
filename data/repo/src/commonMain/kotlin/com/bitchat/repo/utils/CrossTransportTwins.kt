package com.bitchat.repo.utils

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Matches the BLE and LoRa copies of one public mesh message, so it is shown once.
 *
 * **The first copy to arrive is the one shown; a later copy is only ever dropped.** Neither transport
 * authenticates its sender, so a later packet must never be able to change or remove a row that is
 * already on screen: the most a forged copy can do here is get an identical later copy dropped.
 *
 * The LoRa copy carries no message id, only `nickname:content`, so the match is a judgement: two
 * arrivals over different transports with the same text from the same sender, within [window]. A
 * sender is the same device when both sides know a device id and the ids are equal; a nickname only
 * decides when a device id is missing. Pairing is one to one, so a text sent twice is shown twice.
 *
 * What it cannot tell apart: the same sender sending the same text twice within [window] when the
 * first message arrived only over BLE and the second only over LoRa. Those look like one message and
 * are shown once. The window is short to keep that rare; only a message id on the LoRa wire removes it.
 *
 * Arrivals are kept, with their text, until a later call finds them older than [window]; there is no
 * timer. [clear] drops them at once.
 */
internal class CrossTransportTwins(private val window: Duration = 10.seconds) {
    private val unpairedMesh = mutableListOf<Arrival>()
    private val unpairedLoRa = mutableListOf<Arrival>()
    private val droppedMeshIds = mutableMapOf<String, Instant>()

    /**
     * A BLE copy with the mesh message [id] arrived. True when its LoRa twin is already shown, so
     * this copy must not be added; a relayed repeat of that same [id] is then dropped as well.
     */
    fun onMesh(id: String, sender: String, peerId: String?, content: String, now: Instant): Boolean {
        prune(now)
        if (id in droppedMeshIds) return true
        val dropped = pair(Arrival(sender, peerId, content, now), shown = unpairedLoRa, own = unpairedMesh)
        if (dropped) droppedMeshIds[id] = now
        return dropped
    }

    /** A LoRa copy arrived. True when its BLE twin is already shown, so this copy must not be added. */
    fun onLoRa(sender: String, peerId: String?, content: String, now: Instant): Boolean {
        prune(now)
        return pair(Arrival(sender, peerId, content, now), shown = unpairedMesh, own = unpairedLoRa)
    }

    /** Forgets every arrival. Clearing the channel calls this, so no message text outlives it here. */
    fun clear() {
        unpairedMesh.clear()
        unpairedLoRa.clear()
        droppedMeshIds.clear()
    }

    /** Consumes the oldest twin among [shown], or remembers [arrival] in [own] when there is none. */
    private fun pair(arrival: Arrival, shown: MutableList<Arrival>, own: MutableList<Arrival>): Boolean {
        val twin = shown.firstOrNull { it.isTwinOf(arrival) }
        return if (twin != null) {
            shown.remove(twin)
            true
        } else {
            own += arrival
            false
        }
    }

    private fun prune(now: Instant) {
        unpairedMesh.removeAll { now - it.arrivedAt > window }
        unpairedLoRa.removeAll { now - it.arrivedAt > window }
        droppedMeshIds.entries.removeAll { now - it.value > window }
    }

    private data class Arrival(
        val sender: String,
        val peerId: String?,
        val content: String,
        val arrivedAt: Instant,
    ) {
        fun isTwinOf(other: Arrival): Boolean {
            if (content != other.content) return false
            // Two known devices are compared as devices: a shared nickname must not merge two people.
            return if (peerId != null && other.peerId != null) {
                peerId.equals(other.peerId, ignoreCase = true)
            } else {
                sender == other.sender
            }
        }
    }
}
