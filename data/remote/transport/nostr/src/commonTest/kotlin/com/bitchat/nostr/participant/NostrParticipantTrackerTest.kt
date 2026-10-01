package com.bitchat.nostr.participant

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

class NostrParticipantTrackerTest {
    @Test
    fun addsSuffixWhenNicknameCollidesInCurrentGeohash() = runTest {
        val tracker = NostrParticipantTracker()
        val geohash = "u4pruyd"
        val timestamp = Clock.System.now()

        val pubkeyA = "abcdef1234"
        val pubkeyB = "0011aa22bb"

        tracker.updateParticipant(geohash, pubkeyA, "anon", timestamp, isTeleported = false)
        tracker.updateParticipant(geohash, pubkeyB, "anon", timestamp, isTeleported = false)
        tracker.setCurrentGeohash(geohash)

        val people = tracker.currentGeohashPeople.value
        val byId = people.associateBy { it.id }

        val personA = byId[pubkeyA]
        val personB = byId[pubkeyB]

        assertNotNull(personA)
        assertNotNull(personB)
        assertEquals("anon#${pubkeyA.takeLast(4)}", personA.displayName)
        assertEquals("anon#${pubkeyB.takeLast(4)}", personB.displayName)
    }

    /**
     * A stale participant used to crash the app: `getParticipantCountLocked` called
     * `iterator.remove()` and only then read `entry.key`/`entry.value` for its log line, which
     * Kotlin/Native rejects with ConcurrentModificationException once the entry is invalidated.
     * Opening the Locations sheet after a participant aged out was enough to kill the process.
     */
    @Test
    fun prunesStaleParticipantsWithoutConcurrentModification() = runTest {
        val tracker = NostrParticipantTracker()
        val geohash = "u4pruyd"
        val stale = Clock.System.now() - 10.minutes
        val fresh = Clock.System.now()

        tracker.updateParticipant(geohash, "aaaa111122", "stale", stale, isTeleported = false)
        tracker.updateParticipant(geohash, "bbbb333344", "fresh", fresh, isTeleported = false)

        assertEquals(1, tracker.participantCounts.value[geohash], "the stale participant must be pruned")
    }

    @Test
    fun prunesTheLastStaleParticipantOfAGeohash() = runTest {
        val tracker = NostrParticipantTracker()
        val geohash = "9q8yvgb"

        tracker.updateParticipant(geohash, "cccc555566", "only", Clock.System.now() - 10.minutes, isTeleported = false)
        tracker.setCurrentGeohash(geohash)
        tracker.updateParticipant("u4pruyd", "dddd777788", "other", Clock.System.now(), isTeleported = false)

        assertEquals(0, tracker.participantCounts.value[geohash])
        assertEquals(emptyList(), tracker.currentGeohashPeople.value)
    }
}
