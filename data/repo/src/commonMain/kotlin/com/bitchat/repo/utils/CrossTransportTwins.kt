package com.bitchat.repo.utils

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Matches the BLE and LoRa copies of one public mesh message, so it is shown once.
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
 * Unpaired arrivals are kept, with their text, until a later call finds them older than [window];
 * there is no timer. [clear] drops them at once.
 */
internal class CrossTransportTwins(private val window: Duration = 10.seconds) {
    private val unpairedMesh = mutableListOf<Arrival>()
    private val unpairedLoRa = mutableListOf<Arrival>()

    /** A BLE copy arrived. The id of the LoRa row it replaces, or null when it is a new message. */
    fun onMesh(id: String, sender: String, peerId: String?, content: String, now: Instant): String? {
        prune(now)
        val mesh = Arrival(id, sender, peerId, content, now)
        val loRaTwin = unpairedLoRa.firstOrNull { it.isTwinOf(mesh) }
        return if (loRaTwin != null) {
            unpairedLoRa.remove(loRaTwin)
            loRaTwin.id
        } else {
            unpairedMesh += mesh
            null
        }
    }

    /** A LoRa copy arrived. True when its BLE twin is already shown, so this copy must not be added. */
    fun onLoRa(id: String, sender: String, peerId: String?, content: String, now: Instant): Boolean {
        prune(now)
        val loRa = Arrival(id, sender, peerId, content, now)
        val meshTwin = unpairedMesh.firstOrNull { it.isTwinOf(loRa) }
        return if (meshTwin != null) {
            unpairedMesh.remove(meshTwin)
            true
        } else {
            unpairedLoRa += loRa
            false
        }
    }

    /** Forgets every unpaired arrival. The wipe calls this, so no message text outlives it here. */
    fun clear() {
        unpairedMesh.clear()
        unpairedLoRa.clear()
    }

    private fun prune(now: Instant) {
        unpairedMesh.removeAll { now - it.arrivedAt > window }
        unpairedLoRa.removeAll { now - it.arrivedAt > window }
    }

    private data class Arrival(
        val id: String,
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
