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
    // Mesh ids dropped because their LoRa twin was shown, with when and which row that was.
    private val droppedMeshIds = mutableMapOf<String, DroppedFor>()

    /**
     * A BLE copy with the mesh message [id] arrived. True when its LoRa twin is already shown, so
     * this copy must not be added; a relayed repeat of that same [id] is then dropped as well.
     */
    fun onMesh(id: String, sender: String, peerId: String?, content: String, now: Instant): Boolean {
        prune(now)
        if (id in droppedMeshIds) return true
        val twin = pair(Arrival(sender, peerId, content, now, rowId = id), shown = unpairedLoRa, own = unpairedMesh)
        if (twin != null) droppedMeshIds[id] = DroppedFor(twin.rowId, now)
        bound()
        return twin != null
    }

    /**
     * A LoRa copy arrived. True when its BLE twin is already shown, so this copy must not be added.
     * [rowId] is the id of the row this copy becomes when it is shown, for [forget].
     */
    fun onLoRa(sender: String, peerId: String?, content: String, now: Instant, rowId: String? = null): Boolean {
        prune(now)
        val twin = pair(Arrival(sender, peerId, content, now, rowId), shown = unpairedMesh, own = unpairedLoRa)
        bound()
        return twin != null
    }

    /**
     * The rows with these ids are no longer shown (the channel dropped them as its oldest): their
     * arrivals are forgotten, and so is every mesh id that was dropped in favour of one of them, so a
     * copy that still comes is shown instead of being hidden behind nothing.
     */
    fun forget(rowIds: Collection<String>) {
        if (rowIds.isEmpty()) return
        val gone = rowIds.toSet()
        unpairedMesh.removeAll { it.rowId in gone }
        unpairedLoRa.removeAll { it.rowId in gone }
        droppedMeshIds.entries.removeAll { it.value.shownRowId in gone }
    }

    /** Forgets every arrival. Clearing the channel calls this, so no message text outlives it here. */
    fun clear() {
        unpairedMesh.clear()
        unpairedLoRa.clear()
        droppedMeshIds.clear()
    }

    /**
     * Consumes and returns the oldest twin among [shown], or remembers [arrival] in [own] and returns
     * null when there is none.
     */
    private fun pair(arrival: Arrival, shown: MutableList<Arrival>, own: MutableList<Arrival>): Arrival? {
        val twin = shown.firstOrNull { it.isTwinOf(arrival) }
        if (twin != null) shown.remove(twin) else own += arrival
        return twin
    }

    /**
     * Keeps what is remembered small whatever arrives inside the window: past [MAX_ARRIVALS] arrivals
     * or [MAX_ARRIVAL_CHARS] characters on one side, the oldest are forgotten. Forgetting one costs at
     * most a duplicate row; it never hides a message.
     */
    private fun bound() {
        for (arrivals in listOf(unpairedMesh, unpairedLoRa)) {
            var chars = arrivals.sumOf { it.content.length.toLong() }
            while (arrivals.size > MAX_ARRIVALS || chars > MAX_ARRIVAL_CHARS) {
                chars -= arrivals.removeAt(0).content.length
            }
        }
        while (droppedMeshIds.size > MAX_ARRIVALS) droppedMeshIds.remove(droppedMeshIds.keys.first())
    }

    private fun prune(now: Instant) {
        unpairedMesh.removeAll { now - it.arrivedAt > window }
        unpairedLoRa.removeAll { now - it.arrivedAt > window }
        droppedMeshIds.entries.removeAll { now - it.value.at > window }
    }

    private data class Arrival(
        val sender: String,
        val peerId: String?,
        val content: String,
        val arrivedAt: Instant,
        val rowId: String? = null,
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

    private class DroppedFor(val shownRowId: String?, val at: Instant)

    private companion object {
        const val MAX_ARRIVALS = 128
        const val MAX_ARRIVAL_CHARS = 1_000_000L
    }
}
