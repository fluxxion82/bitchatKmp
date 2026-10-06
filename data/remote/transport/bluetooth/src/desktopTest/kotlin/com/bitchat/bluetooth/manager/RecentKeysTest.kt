package com.bitchat.bluetooth.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecentKeysTest {
    @Test
    fun liveKeysAreDuplicatesAndExpiredKeysAreFirstSightingsAgain() {
        val keys = RecentKeys(capacity = 2, timeToLiveMs = 10)
        assertTrue(keys.firstSighting("a", 0))
        assertFalse(keys.firstSighting("a", 9))
        assertTrue(keys.firstSighting("a", 10))
        assertEquals(1, keys.size)
    }

    @Test
    fun aDuplicateDoesNotExtendTheRecord() {
        val keys = RecentKeys(capacity = 2, timeToLiveMs = 10)
        keys.firstSighting("a", 0)
        assertFalse(keys.firstSighting("a", 9))
        assertTrue(keys.firstSighting("a", 10))
    }

    @Test
    fun capacityForgetsTheOldestRecordedKey() {
        val keys = RecentKeys(capacity = 2, timeToLiveMs = 100)
        assertTrue(keys.firstSighting("first", 0))
        assertTrue(keys.firstSighting("second", 1))
        assertTrue(keys.firstSighting("third", 2))
        assertEquals(2, keys.size)
        assertFalse(keys.firstSighting("second", 3))
        assertFalse(keys.firstSighting("third", 3))
        assertTrue(keys.firstSighting("first", 3))
    }

    @Test
    fun aRecordIsForgottenOnlyAfterCapacityManyNewerOnes() {
        val capacity = 64
        val keys = RecentKeys(capacity, timeToLiveMs = 1_000_000)
        keys.firstSighting("H", 0)
        repeat(capacity - 1) { keys.firstSighting("other-$it", 1) }
        assertFalse(keys.firstSighting("H", 2))

        keys.firstSighting("one-more", 3)
        assertTrue(keys.firstSighting("H", 4))
        assertEquals(capacity, keys.size)
    }

    @Test
    fun aKeySeenAgainAfterExpiryIsTheNewestRecord() {
        // The sequence a reviewer used against a table that kept records by position: fill it,
        // let the first key expire and be seen again, then add ONE more key. The refreshed record
        // is the newest, so it is the second key that makes room.
        val capacity = 16
        val keys = RecentKeys(capacity, timeToLiveMs = 60)
        keys.firstSighting("H", 0)
        repeat(capacity - 1) { keys.firstSighting("other-$it", 30) }

        assertTrue(keys.firstSighting("H", 60))
        assertTrue(keys.firstSighting("X", 61))

        assertFalse(keys.firstSighting("H", 62))
        assertFalse(keys.firstSighting("X", 62))
        assertTrue(keys.firstSighting("other-0", 62))
        assertEquals(capacity, keys.size)
    }

    @Test
    fun forgettingAKeyMakesItsNextSightingNew() {
        val keys = RecentKeys(capacity = 2, timeToLiveMs = 10)
        keys.firstSighting("a", 0)
        keys.forget("a")
        assertEquals(0, keys.size)
        assertTrue(keys.firstSighting("a", 1))
    }

    @Test
    fun recordingAndForgettingOneKeyOverAndOverCostsNoOtherKeyItsRecord() {
        val keys = RecentKeys(capacity = 8, timeToLiveMs = 20_000)
        keys.firstSighting("H", 0)
        repeat(10_000) {
            keys.firstSighting("R", (it + 1).toLong())
            keys.forget("R")
        }
        assertFalse(keys.firstSighting("H", 10_001))
        assertEquals(1, keys.size)
    }

    @Test
    fun aKeyRecordedAfterAnotherWasForgottenIsTheNewestRecord() {
        val keys = RecentKeys(capacity = 3, timeToLiveMs = 100)
        keys.firstSighting("a", 0)
        keys.firstSighting("b", 1)
        keys.firstSighting("c", 2)
        keys.forget("a")

        // "d" has room without anyone giving way; "e" then pushes out the oldest, which is "b".
        assertTrue(keys.firstSighting("d", 3))
        assertEquals(3, keys.size)
        assertTrue(keys.firstSighting("e", 4))

        assertFalse(keys.firstSighting("c", 5))
        assertFalse(keys.firstSighting("d", 5))
        assertFalse(keys.firstSighting("e", 5))
        assertEquals(3, keys.size)
        assertTrue(keys.firstSighting("b", 5))
    }

    @Test
    fun forgettingTheOldestTheNewestAndOneInBetweenKeepsTheOrderOfTheRest() {
        val keys = RecentKeys(capacity = 5, timeToLiveMs = 100)
        listOf("a", "b", "c", "d", "e").forEachIndexed { at, key -> keys.firstSighting(key, at.toLong()) }
        keys.forget("a")
        keys.forget("c")
        keys.forget("e")

        // Three free places, then the oldest left ("b") and after it "d" give way, in that order.
        listOf("f", "g", "h").forEach { assertTrue(keys.firstSighting(it, 10)) }
        assertFalse(keys.firstSighting("b", 11))
        assertTrue(keys.firstSighting("i", 12))
        assertTrue(keys.firstSighting("b", 13))
        assertTrue(keys.firstSighting("d", 14))
        assertEquals(5, keys.size)
    }

    @Test
    fun clearingStartsOver() {
        val keys = RecentKeys(capacity = 2, timeToLiveMs = 100)
        keys.firstSighting("a", 0)
        keys.firstSighting("b", 1)
        keys.forget("a")
        keys.clear()

        assertEquals(0, keys.size)
        assertTrue(keys.firstSighting("b", 2))
        assertTrue(keys.firstSighting("c", 3))
        assertTrue(keys.firstSighting("d", 4))
        assertEquals(2, keys.size)
        assertTrue(keys.firstSighting("b", 5))
    }
}
