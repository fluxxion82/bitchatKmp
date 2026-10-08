package com.bitchat.bluetooth.handler

import com.bitchat.bluetooth.protocol.DeliveredNumbers
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The receiver's side of acknowledging private texts that came over the radio: for each peer, the
 * packet numbers still to be acknowledged in the session they were read in ([token]).
 *
 * An entry has exactly one sender loop. [note] returning true is the only thing that starts one,
 * and a loop ends in the same locked step that removes its entry ([snapshot] returning nothing,
 * [sent] returning false, [drop]), never after a wait: so a number noted after a loop ended starts
 * a new loop, and no entry ever has two.
 */
class RadioAckNotes(private val clock: TimeSource, private val maxAgeMs: Long, private val maxPeers: Int = 256) {
    private data class Note(val number: Long, val at: TimeMark)
    private data class Entry(val token: Long, val notes: ArrayDeque<Note>)
    private val lock = SynchronizedObject()
    private val entries = LinkedHashMap<String, Entry>()

    val size: Int get() = synchronized(lock) { entries.size }

    /**
     * True when this number begins a new entry, for which a sender has to be started. Numbers of
     * another session of the same peer are dropped with their entry: they could only be acknowledged
     * in a session that is no longer the one written in. A ninth number pushes out the oldest, which
     * is then never acknowledged; a peer beyond [maxPeers] is not noted at all.
     */
    fun note(peerID: String, token: Long, number: Long): Boolean = synchronized(lock) {
        var entry = entries[peerID]
        if (entry != null && entry.token != token) {
            entries.remove(peerID)
            entry = null
        }
        if (entry == null) {
            if (entries.size >= maxPeers) return@synchronized false
            entries[peerID] = Entry(token, ArrayDeque<Note>()).also { entry = it }
        }
        val notes = requireNotNull(entry).notes
        if (notes.any { it.number == number }) return@synchronized false
        val first = notes.isEmpty()
        if (notes.size == DeliveredNumbers.MAX) notes.removeFirst()
        notes.addLast(Note(number, clock.markNow()))
        first
    }

    /** The numbers to acknowledge now, oldest first; those noted too long ago are dropped. Nothing left: the entry is gone. */
    fun snapshot(peerID: String, token: Long): List<Long> = synchronized(lock) {
        val entry = entries[peerID] ?: return@synchronized emptyList()
        if (entry.token != token) return@synchronized emptyList()
        while (entry.notes.firstOrNull()?.at?.elapsedNow()?.inWholeMilliseconds?.let { it > maxAgeMs } == true) entry.notes.removeFirst()
        if (entry.notes.isEmpty()) entries.remove(peerID)
        entry.notes.map { it.number }
    }

    /** Forgets [numbers], which have gone out. True when others noted meanwhile remain; false when the entry is gone. */
    fun sent(peerID: String, token: Long, numbers: List<Long>): Boolean = synchronized(lock) {
        val entry = entries[peerID] ?: return@synchronized false
        if (entry.token != token) return@synchronized false
        entry.notes.removeAll { it.number in numbers }
        if (entry.notes.isEmpty()) entries.remove(peerID)
        entry.notes.isNotEmpty()
    }

    fun drop(peerID: String, token: Long) = synchronized(lock) {
        if (entries[peerID]?.token == token) entries.remove(peerID)
    }
}

/**
 * The sender's side: which message a packet number of a session ([token]) of a peer carried, for the
 * texts offered to the radio, until the peer acknowledges that number in that session. Only what
 * this device sends makes an entry; what is received can only take entries away. An entry goes when
 * it is confirmed, when it is too old, or when a newer one needs its place: what was sent in a session
 * that no longer stands can never be confirmed and leaves in one of the last two ways.
 */
class SentRadioTexts(private val clock: TimeSource, private val maxAgeMs: Long = 600_000, private val capacity: Int = 64) {
    private data class Key(val peerID: String, val token: Long, val number: Long)
    private data class Entry(val messageID: String, val at: TimeMark)
    private val lock = SynchronizedObject()
    private val entries = LinkedHashMap<Key, Entry>()

    val size: Int get() = synchronized(lock) { entries.size }

    fun remember(peerID: String, token: Long, number: Long, messageID: String) = synchronized(lock) {
        while (entries.size >= capacity) entries.remove(entries.entries.first().key)
        entries[Key(peerID, token, number)] = Entry(messageID, clock.markNow())
    }

    /** The messages those numbers carried, each given once: a number of another peer or session, or one too old, gives nothing. */
    fun resolve(peerID: String, token: Long, numbers: List<Long>): List<String> = synchronized(lock) {
        val found = mutableListOf<String>()
        numbers.forEach { number ->
            val key = Key(peerID, token, number)
            val entry = entries.remove(key) ?: return@forEach
            if (entry.at.elapsedNow().inWholeMilliseconds <= maxAgeMs) found += entry.messageID
        }
        found
    }
}
