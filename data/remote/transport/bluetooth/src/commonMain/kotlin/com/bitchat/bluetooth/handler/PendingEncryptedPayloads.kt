package com.bitchat.bluetooth.handler

import com.bitchat.bluetooth.protocol.MAX_PENDING_BYTES_PER_PEER
import com.bitchat.bluetooth.protocol.MAX_PENDING_PAYLOADS_PER_PEER
import com.bitchat.bluetooth.protocol.MAX_PENDING_PEERS
import com.bitchat.bluetooth.protocol.MAX_PENDING_PEERS_PER_LINK
import com.bitchat.bluetooth.protocol.PENDING_PAYLOAD_MAX_AGE_MS
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock

/**
 * Encrypted payloads kept for a handshake that may make them readable.
 *
 * Room is given out in places. A place belongs to ONE sender id ON ONE link (the address of the
 * connection the payload arrived on), holds a few payloads, and ends when its last payload has been
 * taken, dropped or has aged out. Nothing kept is ever pushed out to make room; when a limit is
 * reached the new payload is refused.
 *
 * The link is part of the place because the id is whatever the packet claimed. Payloads under one
 * id that arrive over two links are two places, each counted against its own link, so what arrives
 * on one link can neither use nor prolong a place on another. A link has 16 places: filling them
 * takes 16 sender ids that each had a handshake open when they offered on that link, renewed every
 * 30 seconds; then another peer's early payload over that same link is refused, and other links are
 * unaffected. All 128 places take eight links.
 */
class PendingEncryptedPayloads(
    private val maxPerPeer: Int = MAX_PENDING_PAYLOADS_PER_PEER,
    private val maxBytesPerPeer: Int = MAX_PENDING_BYTES_PER_PEER,
    private val maxPeers: Int = MAX_PENDING_PEERS,
    private val maxPeersPerLink: Int = MAX_PENDING_PEERS_PER_LINK,
    private val maxAgeMs: Long = PENDING_PAYLOAD_MAX_AGE_MS
) {
    /** A kept payload and the link it arrived on. */
    class Kept(val payload: ByteArray, val link: String)

    private data class PlaceKey(val link: String, val peerID: String)
    private class Entry(val payload: ByteArray, val link: String, val receivedAt: Long, val arrival: Long)

    private val lock = ReentrantLock()
    private val places = LinkedHashMap<PlaceKey, MutableList<Entry>>()
    private val placesByLink = mutableMapOf<String, Int>()
    private var arrivals = 0L
    private var totalCount = 0
    private var totalBytes = 0

    val count: Int get() = lock.withLock { totalCount }
    val bytes: Int get() = lock.withLock { totalBytes }
    internal val trackedLinkCount: Int get() = lock.withLock { placesByLink.size }

    /** Keeps [payload] only when this id has room of its own on [link] and a place is available. */
    fun offer(peerID: String, payload: ByteArray, now: Long, link: String = ""): Boolean = lock.withLock {
        forgetExpired(now)
        if (payload.size > maxBytesPerPeer) return@withLock false
        val key = PlaceKey(link, peerID)
        val held = places[key]
        val heldBytes = held?.sumOf { it.payload.size } ?: 0
        if ((held?.size ?: 0) >= maxPerPeer || heldBytes + payload.size > maxBytesPerPeer) return@withLock false
        if (held == null) {
            if ((placesByLink[link] ?: 0) >= maxPeersPerLink) return@withLock false
            if (places.size >= maxPeers) return@withLock false
        }

        val place = held ?: mutableListOf<Entry>().also {
            places[key] = it
            placesByLink[link] = (placesByLink[link] ?: 0) + 1
        }
        place.add(Entry(payload, link, now, arrivals++))
        totalCount += 1
        totalBytes += payload.size
        true
    }

    /** Everything kept for [peerID], whatever link it arrived on, in the order it arrived. */
    fun take(peerID: String, now: Long): List<Kept> = lock.withLock {
        forgetExpired(now)
        removePlacesOf(peerID).sortedBy { it.arrival }.map { Kept(it.payload, it.link) }
    }

    fun drop(peerID: String) {
        lock.withLock { removePlacesOf(peerID) }
    }

    private fun removePlacesOf(peerID: String): List<Entry> {
        val removed = mutableListOf<Entry>()
        val iterator = places.entries.iterator()
        while (iterator.hasNext()) {
            val (key, entries) = iterator.next()
            if (key.peerID != peerID) continue
            iterator.remove()
            freePlace(key.link)
            totalCount -= entries.size
            totalBytes -= entries.sumOf { it.payload.size }
            removed += entries
        }
        return removed
    }

    private fun forgetExpired(now: Long) {
        val iterator = places.entries.iterator()
        while (iterator.hasNext()) {
            val (key, entries) = iterator.next()
            val kept = entries.iterator()
            while (kept.hasNext()) {
                val entry = kept.next()
                if (now - entry.receivedAt >= maxAgeMs) {
                    kept.remove()
                    totalCount -= 1
                    totalBytes -= entry.payload.size
                }
            }
            if (entries.isEmpty()) {
                iterator.remove()
                freePlace(key.link)
            }
        }
    }

    private fun freePlace(link: String) {
        val remaining = (placesByLink[link] ?: return) - 1
        if (remaining == 0) placesByLink.remove(link) else placesByLink[link] = remaining
    }
}
