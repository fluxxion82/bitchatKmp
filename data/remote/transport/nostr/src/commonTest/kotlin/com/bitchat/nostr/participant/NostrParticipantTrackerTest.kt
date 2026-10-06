package com.bitchat.nostr.participant

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class NostrParticipantTrackerTest {
    @Test
    fun clampsFutureTimestampToTheLocalClock() = runTest {
        val clock = TestClock(Instant.fromEpochSeconds(1_000))
        val tracker = NostrParticipantTracker(clock)
        tracker.updateParticipant("u4pruyd", "future", "future", clock.now() + (24 * 60).minutes, false)

        clock.instant += 6.minutes
        tracker.updateParticipant("elsewhere", "fresh", "fresh", clock.now(), false)

        assertEquals(null, tracker.getNicknameByPubkey("future"))
    }

    @Test
    fun sanitizesStoredNicknameAndForgetsItWhenParticipantAgesOut() = runTest {
        val clock = TestClock(Instant.fromEpochSeconds(1_000))
        val tracker = NostrParticipantTracker(clock)
        tracker.updateParticipant("u4pruyd", "peer", "a\u0007lice", clock.now(), true)
        assertEquals("alice", tracker.getNicknameByPubkey("peer"))
        assertEquals("alice", tracker.getNicknameByPubkeySync("peer"))

        clock.instant += 6.minutes
        tracker.updateParticipant("elsewhere", "fresh", "fresh", clock.now(), false)
        assertEquals(null, tracker.getNicknameByPubkey("peer"))
        assertEquals(null, tracker.getNicknameByPubkeySync("peer"))
    }

    @Test
    fun anOlderEventAboutAKnownKeyNeitherMovesItBackNorRenamesIt() = runTest {
        val clock = TestClock(Instant.fromEpochSeconds(1_000))
        val tracker = NostrParticipantTracker(clock)
        tracker.updateParticipant("u4pruyd", "peer", "alice", clock.now(), false)

        // Replayed from four minutes earlier, under another name.
        tracker.updateParticipant("u4pruyd", "peer", "mallory", clock.now() - 4.minutes, false)
        assertEquals("alice", tracker.getNicknameByPubkey("peer"))

        // Two minutes on it is still inside the five minutes; moved back, it would be gone by now.
        clock.instant += 2.minutes
        tracker.setCurrentGeohash("u4pruyd")
        assertEquals(listOf("peer"), tracker.currentGeohashPeople.value.map { it.id })
    }

    @Test
    fun aClockSetBackNeitherFreezesAParticipantNorKeepsItPastItsFiveMinutes() = runTest {
        val clock = TestClock(Instant.fromEpochSeconds(100_000))
        val tracker = NostrParticipantTracker(clock)
        tracker.updateParticipant("u4pruyd", "peer", "before", clock.now(), false)

        // The device's clock is corrected by an hour, backwards. The participant posts again.
        clock.instant -= 60.minutes
        tracker.updateParticipant("u4pruyd", "peer", "after", clock.now(), false)
        assertEquals("after", tracker.getNicknameByPubkey("peer"), "an update dated now is not older than what is known")

        // Six minutes later by that clock it has aged out, not an hour and six minutes later.
        clock.instant += 6.minutes
        tracker.updateParticipant("elsewhere", "fresh", "fresh", clock.now(), false)
        assertEquals(null, tracker.getNicknameByPubkey("peer"))
    }

    @Test
    fun aKeyThatIsAlreadyPastTheFiveMinutesCostsNobodyTheirPlace() = runTest {
        val clock = TestClock(Instant.fromEpochSeconds(100_000))
        val tracker = NostrParticipantTracker(clock, maxParticipantsPerGeohash = 2)
        tracker.setCurrentGeohash("u4pruyd")
        tracker.updateParticipant("u4pruyd", "first", "first", clock.now(), false)
        tracker.updateParticipant("u4pruyd", "second", "second", clock.now(), false)

        // The list is full. A new key arrives dated six minutes ago: it would be dropped at once.
        tracker.updateParticipant("u4pruyd", "backdated", "backdated", clock.now() - 6.minutes, false)

        assertEquals(setOf("first", "second"), tracker.currentGeohashPeople.value.map { it.id }.toSet())
        assertEquals(null, tracker.getNicknameByPubkey("backdated"))
    }

    @Test
    fun aKeyDatedAheadGetsNoAdvantageOverThoseSeenNow() = runTest {
        val clock = TestClock(Instant.fromEpochSeconds(100_000))
        val tracker = NostrParticipantTracker(clock, maxParticipantsPerGeohash = 2)
        tracker.setCurrentGeohash("u4pruyd")
        tracker.updateParticipant("u4pruyd", "first", "first", clock.now(), false)
        tracker.updateParticipant("u4pruyd", "second", "second", clock.now(), false)

        // Dated an hour ahead it is still only seen now: no later than those here, who came first.
        tracker.updateParticipant("u4pruyd", "ahead", "ahead", clock.now() + 60.minutes, false)

        assertEquals(setOf("first", "second"), tracker.currentGeohashPeople.value.map { it.id }.toSet())
    }

    @Test
    fun aNewKeySeenNoLaterThanEveryoneInAFullListIsNotTakenIn() = runTest {
        val clock = TestClock(Instant.fromEpochSeconds(100_000))
        val tracker = NostrParticipantTracker(clock, maxParticipantsPerGeohash = 2)
        tracker.setCurrentGeohash("u4pruyd")
        tracker.updateParticipant("u4pruyd", "first", "first", clock.now() - 1.minutes, false)
        tracker.updateParticipant("u4pruyd", "second", "second", clock.now(), false)

        // Dated two minutes ago: live, but seen longer ago than both who are here.
        tracker.updateParticipant("u4pruyd", "older", "older", clock.now() - 2.minutes, false)
        assertEquals(setOf("first", "second"), tracker.currentGeohashPeople.value.map { it.id }.toSet())

        // Dated now it is newer than the one seen longest ago, who makes room.
        tracker.updateParticipant("u4pruyd", "newer", "newer", clock.now(), false)
        assertEquals(setOf("second", "newer"), tracker.currentGeohashPeople.value.map { it.id }.toSet())
    }

    @Test
    fun evictsOldestNewParticipantButNotAnUpdate() = runTest {
        val clock = TestClock(Instant.fromEpochSeconds(1_000))
        val tracker = NostrParticipantTracker(clock, maxParticipantsPerGeohash = 2)
        tracker.updateParticipant("u4pruyd", "old", "old", clock.now(), false)
        clock.instant += 1.minutes
        tracker.updateParticipant("u4pruyd", "kept", "kept", clock.now(), false)
        clock.instant += 1.minutes
        tracker.updateParticipant("u4pruyd", "old", "renamed", clock.now(), false)
        clock.instant += 1.minutes
        tracker.updateParticipant("u4pruyd", "new", "new", clock.now(), false)

        assertEquals("renamed", tracker.getNicknameByPubkey("old"))
        assertEquals(null, tracker.getNicknameByPubkey("kept"))
        assertEquals("new", tracker.getNicknameByPubkey("new"))
    }
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

private class TestClock(var instant: Instant) : Clock {
    override fun now(): Instant = instant
}
