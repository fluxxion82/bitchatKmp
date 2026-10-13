package com.bitchat.bluetooth.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeardAdvertisersTest {

    private val board = "90:82:8D:69:79:2D"
    private val phone = "6E:29:7F:0C:24:8D"
    private val other = "5D:7E:6C:61:2B:6E"

    @Test
    fun anAddressIsHeardUntilTheWindowEnds() {
        val advertisers = HeardAdvertisers(windowMs = 10L)

        advertisers.onHeard(board, now = 100L)

        assertTrue(advertisers.isHeard(board, now = 109L))
        assertFalse(advertisers.isHeard(board, now = 110L))
    }

    @Test
    fun aNewReportStartsTheWindowAgain() {
        val advertisers = HeardAdvertisers(windowMs = 10L)

        advertisers.onHeard(board, now = 100L)
        advertisers.onHeard(board, now = 109L)

        assertTrue(advertisers.isHeard(board, now = 118L))
        assertFalse(advertisers.isHeard(board, now = 119L))
    }

    @Test
    fun anAddressNeverReportedIsNotHeard() {
        val advertisers = HeardAdvertisers(windowMs = 10L)

        assertFalse(advertisers.isHeard(board, now = 100L))
    }

    @Test
    fun anAddressBlueZDroppedIsNotHeardUntilReportedAgain() {
        val advertisers = HeardAdvertisers(windowMs = 10L)

        advertisers.onHeard(board, now = 100L)
        advertisers.onRemoved(board)

        assertFalse(advertisers.isHeard(board, now = 101L))

        advertisers.onHeard(board, now = 102L)

        assertTrue(advertisers.isHeard(board, now = 103L))
    }

    @Test
    fun recentListsHeardAddressesInTheOrderFirstHeard() {
        val advertisers = HeardAdvertisers(windowMs = 10L)

        advertisers.onHeard(board, now = 100L)
        advertisers.onHeard(phone, now = 101L)
        advertisers.onHeard(other, now = 102L)
        advertisers.onHeard(board, now = 103L)

        assertEquals(listOf(board, phone, other), advertisers.recent(now = 109L))
        assertEquals(listOf(board), advertisers.recent(now = 112L))
    }

    @Test
    fun aFullTableMakesRoomOnlyForAddressesNoLongerHeard() {
        val advertisers = HeardAdvertisers(windowMs = 10L, maxTracked = 2)

        advertisers.onHeard(board, now = 100L)
        advertisers.onHeard(phone, now = 101L)
        advertisers.onHeard(other, now = 102L)

        assertEquals(listOf(board, phone), advertisers.recent(now = 102L))
        assertEquals(listOf(phone), advertisers.recent(now = 110L))

        advertisers.onHeard(other, now = 110L)

        assertEquals(listOf(phone, other), advertisers.recent(now = 110L))
    }

    @Test
    fun aRefusedNewcomerIsRecordedOnceThereIsRoom() {
        val advertisers = HeardAdvertisers(windowMs = 10L, maxTracked = 2)

        advertisers.onHeard(board, now = 100L)
        advertisers.onHeard(phone, now = 101L)
        advertisers.onHeard(other, now = 102L)
        advertisers.onHeard(other, now = 111L)

        assertEquals(listOf(other), advertisers.recent(now = 111L))
    }
}
