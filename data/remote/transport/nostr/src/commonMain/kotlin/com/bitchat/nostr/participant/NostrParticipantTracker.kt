package com.bitchat.nostr.participant

import com.bitchat.nostr.logging.logNostrDebug
import com.bitchat.nostr.model.NostrParticipant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class NostrParticipantTracker {
    private val mutex = Mutex()

    // geohash -> (pubkeyHex -> lastSeen)
    private val participants = mutableMapOf<String, MutableMap<String, Instant>>()

    // pubkeyHex -> nickname
    private val nicknames = mutableMapOf<String, String>()

    // teleported pubkeys
    private val teleported = mutableSetOf<String>()

    private var currentGeohash: String? = null

    private val _participantCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val participantCounts: StateFlow<Map<String, Int>> = _participantCounts.asStateFlow()

    private val _currentGeohashPeople = MutableStateFlow<List<NostrParticipant>>(emptyList())
    val currentGeohashPeople: StateFlow<List<NostrParticipant>> = _currentGeohashPeople.asStateFlow()

    suspend fun updateParticipant(geohash: String, pubkey: String, nickname: String, timestamp: Instant, isTeleported: Boolean) =
        mutex.withLock {
            val participantsMap = participants.getOrPut(geohash) { mutableMapOf() }
            participantsMap[pubkey.lowercase()] = timestamp

            nicknames[pubkey.lowercase()] = nickname

            if (isTeleported) {
                teleported.add(pubkey.lowercase())
            }

            logNostrDebug(
                "ParticipantTracker",
                "Updated participant ${shortPubkey(pubkey)} (nick='$nickname', geohash=$geohash, teleported=$isTeleported)"
            )

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

    private fun getParticipantCountLocked(geohash: String): Int {
        val cutoff = Clock.System.now() - 5.minutes
        val participantsMap = participants[geohash] ?: return 0

        // Remove expired entries using iterator (multiplatform-compatible)
        val iterator = participantsMap.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value < cutoff) {
                iterator.remove()
                val name = nicknames[entry.key] ?: "anon"
                val ageSeconds = Clock.System.now().minus(entry.value).inWholeSeconds
                logNostrDebug(
                    "ParticipantTracker",
                    "Removed stale participant ${shortPubkey(entry.key)} ($name) from geohash=$geohash, lastSeen=${entry.value}, age=${ageSeconds}s"
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
        val cutoff = Clock.System.now() - 5.minutes
        val participantsMap = participants[geohash] ?: emptyMap()

        val activeParticipants = participantsMap.filter { (_, lastSeen) -> lastSeen > cutoff }
        val baseNames = activeParticipants.mapValues { (pubkey, _) ->
            nicknames[pubkey]?.trim().takeUnless { it.isNullOrEmpty() } ?: "anon"
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
    }

    suspend fun clear() = mutex.withLock {
        participants.clear()
        nicknames.clear()
        teleported.clear()
        _participantCounts.value = emptyMap()
        _currentGeohashPeople.value = emptyList()
    }

    /**
     * Look up a cached nickname by pubkey (suspend version with mutex lock).
     * Returns null if the pubkey is not known or has no nickname.
     */
    suspend fun getNicknameByPubkey(pubkeyHex: String): String? = mutex.withLock {
        nicknames[pubkeyHex.lowercase()]
    }

    /**
     * Look up a cached nickname by pubkey (synchronous version without mutex lock).
     * Safe for non-critical display name lookups where slight staleness is acceptable.
     * Returns null if the pubkey is not known or has no nickname.
     */
    fun getNicknameByPubkeySync(pubkeyHex: String): String? {
        return nicknames[pubkeyHex.lowercase()]
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
