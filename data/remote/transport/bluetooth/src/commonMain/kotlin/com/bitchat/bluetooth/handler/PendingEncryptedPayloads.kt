package com.bitchat.bluetooth.handler

import com.bitchat.bluetooth.protocol.MAX_PENDING_BYTES_PER_PEER
import com.bitchat.bluetooth.protocol.MAX_PENDING_PAYLOADS_PER_PEER
import com.bitchat.bluetooth.protocol.MAX_PENDING_PEERS
import com.bitchat.bluetooth.protocol.PENDING_PAYLOAD_MAX_AGE_MS
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock

/**
 * Encrypted payloads kept for a handshake that may make them readable. One peer's payloads cannot
 * cost another peer its room; the only thing shared is the number of peers. Holding all 128 places
 * takes 128 sender ids that each had a handshake open when they offered, renewed every 30 seconds.
 */
class PendingEncryptedPayloads(
    private val maxPerPeer: Int = MAX_PENDING_PAYLOADS_PER_PEER,
    private val maxBytesPerPeer: Int = MAX_PENDING_BYTES_PER_PEER,
    private val maxPeers: Int = MAX_PENDING_PEERS,
    private val maxAgeMs: Long = PENDING_PAYLOAD_MAX_AGE_MS
) {
    private class Entry(val payload: ByteArray, val receivedAt: Long)

    private val lock = ReentrantLock()
    private val entries = LinkedHashMap<String, MutableList<Entry>>()
    private var totalCount = 0
    private var totalBytes = 0

    val count: Int get() = lock.withLock { totalCount }
    val bytes: Int get() = lock.withLock { totalBytes }

    /** Keeps [payload] only when [peerID] has room of its own and a peer place is available. */
    fun offer(peerID: String, payload: ByteArray, now: Long): Boolean = lock.withLock {
        forgetExpired(now)
        if (payload.size > maxBytesPerPeer) return@withLock false
        val peerEntries = entries[peerID]
        val peerCount = peerEntries?.size ?: 0
        val peerBytes = peerEntries?.sumOf { it.payload.size } ?: 0
        if (peerCount >= maxPerPeer || peerBytes + payload.size > maxBytesPerPeer) return@withLock false
        if (peerEntries == null && entries.size >= maxPeers) return@withLock false

        entries.getOrPut(peerID) { mutableListOf() }.add(Entry(payload, now))
        totalCount += 1
        totalBytes += payload.size
        true
    }

    fun take(peerID: String, now: Long): List<ByteArray> = lock.withLock {
        forgetExpired(now)
        val values = entries.remove(peerID) ?: return@withLock emptyList()
        totalCount -= values.size
        totalBytes -= values.sumOf { it.payload.size }
        values.map { it.payload }
    }

    fun drop(peerID: String) = lock.withLock {
        val values = entries.remove(peerID) ?: return@withLock
        totalCount -= values.size
        totalBytes -= values.sumOf { it.payload.size }
    }

    private fun forgetExpired(now: Long) {
        val peers = entries.values.iterator()
        while (peers.hasNext()) {
            val payloads = peers.next()
            val kept = payloads.iterator()
            while (kept.hasNext()) {
                val entry = kept.next()
                if (now - entry.receivedAt >= maxAgeMs) {
                    kept.remove()
                    totalCount -= 1
                    totalBytes -= entry.payload.size
                }
            }
            if (payloads.isEmpty()) peers.remove()
        }
    }
}
