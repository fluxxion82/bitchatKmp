package com.bitchat.nostr.participant

import com.bitchat.nostr.logging.logNostrDebug
import com.bitchat.nostr.model.NostrParticipant
import com.bitchat.nostr.util.sanitizedNickname
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class NostrParticipantTracker(
    private val clock: Clock = Clock.System,
    private val maxParticipantsPerGeohash: Int = 1337,
) {
    init {
        require(maxParticipantsPerGeohash > 0) {
            "maxParticipantsPerGeohash must be positive, was $maxParticipantsPerGeohash"
        }
    }

    private val mutex = Mutex()

    // geohash -> (pubkeyHex -> lastSeen)
    private val participants = mutableMapOf<String, MutableMap<String, Instant>>()

    // pubkeyHex -> nickname
    private val nicknames = mutableMapOf<String, String>()
    private val nicknamesLock = SynchronizedObject()

    // teleported pubkeys
    private val teleported = mutableSetOf<String>()

    private var currentGeohash: String? = null

    private val _participantCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val participantCounts: StateFlow<Map<String, Int>> = _participantCounts.asStateFlow()

    private val _currentGeohashPeople = MutableStateFlow<List<NostrParticipant>>(emptyList())
    val currentGeohashPeople: StateFlow<List<NostrParticipant>> = _currentGeohashPeople.asStateFlow()

    suspend fun updateParticipant(geohash: String, pubkey: String, nickname: String, timestamp: Instant, isTeleported: Boolean) =
        mutex.withLock {
            val normalizedPubkey = pubkey.lowercase()
            val now = clock.now()
            val participantsMap = participants.getOrPut(geohash) { mutableMapOf() }
            // First drop whoever is past the five minutes here (and bring back times a clock set
            // back has left ahead of now), so what follows is decided among live participants only.
            getParticipantCountLocked(geohash)

            // The sender writes the timestamp. One dated ahead would keep its key "present" for ever
            // and make it the last to be dropped, so no key is seen later than now.
            if (admitLocked(participantsMap, normalizedPubkey, seen = minOf(timestamp, now))) {
                val name = sanitizedNickname(nickname) ?: "anon"
                synchronized(nicknamesLock) { nicknames[normalizedPubkey] = name }

                if (isTeleported) {
                    teleported.add(normalizedPubkey)
                }

                logNostrDebug(
                    "ParticipantTracker",
                    "Updated participant ${shortPubkey(pubkey)} (nick='$name', geohash=$geohash, teleported=$isTeleported)"
                )
            }

            updateCountsLocked()
            if (geohash == currentGeohash) {
                refreshCurrentGeohashPeopleLocked()
            }
        }

    suspend fun setCurrentGeohash(geohash: String?) = mutex.withLock {
        currentGeohash = geohash
        logNostrDebug("ParticipantTracker", "Current geohash set to ${geohash ?: "none"}")
        if (currentGeohash == null) {
            _currentGeohashPeople.value = emptyList()
            return@withLock
        }

        refreshCurrentGeohashPeopleLocked()
    }

    /**
     * Records that [pubkey] was [seen] among [participantsMap], which holds live participants only.
     * False when the event says nothing worth keeping, and then nothing has changed:
     *  - it is older than what is known of that key: a replay must not move a key back toward the
     *    cutoff, or rename it;
     *  - the geohash is full and this new key would itself be the one seen longest ago (so also any
     *    new key already past the five minutes: it cannot cost a live participant its place).
     *    Otherwise the one seen longest ago makes room. A key already here evicts nobody.
     * A new key past the five minutes that does find room is dropped by the pruning that follows.
     */
    private fun admitLocked(participantsMap: MutableMap<String, Instant>, pubkey: String, seen: Instant): Boolean {
        val knownSince = participantsMap[pubkey]
        if (knownSince != null) {
            if (seen < knownSince) return false
        } else {
            if (participantsMap.size >= maxParticipantsPerGeohash) {
                val oldest = participantsMap.minByOrNull { it.value } ?: return false
                if (seen <= oldest.value) return false
                participantsMap.remove(oldest.key)
            }
        }
        participantsMap[pubkey] = seen
        return true
    }

    private fun getParticipantCountLocked(geohash: String): Int {
        val now = clock.now()
        val cutoff = now - 5.minutes
        val participantsMap = participants[geohash] ?: return 0

        // This device's clock can be set back (the boards have no battery clock). A time stored before
        // that is then later than now: nothing new could be newer than it, so its key could no longer
        // be updated, and it would outlive its five minutes by as much as the clock moved. No stored
        // time stays ahead of now.
        participantsMap.filterValues { it > now }.keys.forEach { participantsMap[it] = now }

        // Remove expired entries using iterator (multiplatform-compatible). Read the key and the
        // timestamp BEFORE removing: Kotlin/Native invalidates the entry on remove(), so touching
        // it afterwards (as the log line below does) throws ConcurrentModificationException and
        // killed the app whenever a participant aged out. The JVM's HashMap tolerates it, so this
        // only ever crashed on iOS and the embedded binaries.
        val iterator = participantsMap.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val pubkey = entry.key
            val lastSeen = entry.value
            if (lastSeen < cutoff) {
                iterator.remove()
                val name = synchronized(nicknamesLock) { nicknames[pubkey] } ?: "anon"
                val ageSeconds = now.minus(lastSeen).inWholeSeconds
                logNostrDebug(
                    "ParticipantTracker",
                    "Removed stale participant ${shortPubkey(pubkey)} ($name) from geohash=$geohash, lastSeen=$lastSeen, age=${ageSeconds}s"
                )
            }
        }

        return participantsMap.size
    }

    private fun refreshCurrentGeohashPeopleLocked() {
        val geohash = currentGeohash ?: run {
            _currentGeohashPeople.value = emptyList()
            return
        }
        val previous = _currentGeohashPeople.value
        val cutoff = clock.now() - 5.minutes
        val participantsMap = participants[geohash] ?: emptyMap()

        val activeParticipants = participantsMap.filter { (_, lastSeen) -> lastSeen > cutoff }
        val baseNames = activeParticipants.mapValues { (pubkey, _) ->
            synchronized(nicknamesLock) { nicknames[pubkey] }?.trim().takeUnless { it.isNullOrEmpty() } ?: "anon"
        }
        val nameCounts = baseNames.values
            .groupingBy { it.lowercase() }
            .eachCount()

        val people = activeParticipants
            .map { (pubkey, lastSeen) ->
                val base = baseNames[pubkey] ?: "anon"
                val displayName = if ((nameCounts[base.lowercase()] ?: 0) > 1) {
                    "$base#${pubkey.takeLast(4)}"
                } else {
                    base
                }

                NostrParticipant(
                    id = pubkey,
                    displayName = displayName,
                    lastSeen = lastSeen
                )
            }
            .sortedByDescending { it.lastSeen }

        _currentGeohashPeople.value = people
        logGeohashSnapshot(geohash, previous, people)
    }

    private fun updateCountsLocked() {
        val counts = participants.mapValues { (geohash, _) ->
            getParticipantCountLocked(geohash)
        }
        _participantCounts.value = counts
        // What is kept about a key goes when the key is a participant nowhere any more.
        val activePubkeys = participants.values.flatMap { it.keys }.toSet()
        synchronized(nicknamesLock) {
            nicknames.keys.retainAll(activePubkeys)
        }
        teleported.retainAll(activePubkeys)
    }

    suspend fun clear() = mutex.withLock {
        participants.clear()
        synchronized(nicknamesLock) { nicknames.clear() }
        teleported.clear()
        _participantCounts.value = emptyMap()
        _currentGeohashPeople.value = emptyList()
    }

    /**
     * Look up a cached nickname by pubkey (suspend version with mutex lock).
     * Returns null if the pubkey is not known or has no nickname.
     */
    suspend fun getNicknameByPubkey(pubkeyHex: String): String? = mutex.withLock {
        synchronized(nicknamesLock) { nicknames[pubkeyHex.lowercase()] }
    }

    /**
     * Look up a cached nickname by pubkey (synchronous version without mutex lock).
     * Safe for non-critical display name lookups where slight staleness is acceptable.
     * Returns null if the pubkey is not known or has no nickname.
     */
    fun getNicknameByPubkeySync(pubkeyHex: String): String? {
        return synchronized(nicknamesLock) { nicknames[pubkeyHex.lowercase()] }
    }

    private fun logGeohashSnapshot(geohash: String, previous: List<NostrParticipant>, current: List<NostrParticipant>) {
        val previousIds = previous.map { it.id }.toSet()
        val currentIds = current.map { it.id }.toSet()
        val added = current.filter { it.id !in previousIds }
        val removed = previous.filter { it.id !in currentIds }
        val summary = current.joinToString { "${it.displayName} (${shortPubkey(it.id)})" }

        logNostrDebug(
            "ParticipantTracker",
            "Geohash=$geohash participants=${current.size} [${summary.ifEmpty { "none" }}]"
        )

        if (added.isNotEmpty()) {
            val addedText = added.joinToString { "${it.displayName} (${shortPubkey(it.id)})" }
            logNostrDebug("ParticipantTracker", "   Added: $addedText")
        }

        if (removed.isNotEmpty()) {
            val removedText = removed.joinToString { "${it.displayName} (${shortPubkey(it.id)})" }
            logNostrDebug("ParticipantTracker", "   Removed: $removedText")
        }
    }

    private fun shortPubkey(pubkey: String): String {
        val normalized = pubkey.lowercase()
        return if (normalized.length <= 16) normalized else normalized.substring(0, 16)
    }
}
