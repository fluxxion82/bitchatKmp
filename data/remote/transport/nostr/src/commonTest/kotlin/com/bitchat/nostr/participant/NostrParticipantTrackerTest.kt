package com.bitchat.nostr.participant

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Clock

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
}
