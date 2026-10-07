package com.bitchat.domain.chat.model

import kotlin.test.Test
import kotlin.test.assertEquals

class SendTimesTest {

    @Test
    fun messagesHandedOverAtTheSameMomentAreDatedOneApartInOrder() {
        assertEquals(listOf(1_000L, 1_001L, 1_002L, 1_003L), datesOf(4, now = 1_000))
    }

    @Test
    fun theTimeIsTheClocksAgainOnceItHasCaughtUp() {
        val last = datesOf(3, now = 1_000).last()

        assertEquals(1_010, nextSendTime(last, now = 1_010, maxAhead = 60))
        assertEquals(1_011, nextSendTime(1_010, now = 1_010, maxAhead = 60))
    }

    @Test
    fun aClockSetBackALittleDoesNotMakeALaterMessageEarlier() {
        assertEquals(1_001, nextSendTime(last = 1_000, now = 990, maxAhead = 60))
        assertEquals(1_002, nextSendTime(last = 1_001, now = 990, maxAhead = 60))
    }

    @Test
    fun aClockSetBackFurtherThanTheLimitIsFollowedAgain() {
        // A board without a battery clock: the time it had was wrong by more than a receiver allows.
        assertEquals(900, nextSendTime(last = 1_000, now = 900, maxAhead = 60))
        assertEquals(901, nextSendTime(last = 900, now = 900, maxAhead = 60))
    }

    @Test
    fun theTimeNeverRunsFurtherAheadOfTheClockThanTheLimit() {
        val dates = datesOf(200, now = 1_000, maxAhead = 60)

        assertEquals((1_000L..1_060L).toList(), dates.take(61))
        assertEquals(1_060L, dates.max())
        // Past the limit it starts over at the clock, one apart as before.
        assertEquals((1_000L..1_060L).toList(), dates.drop(61).take(61))
    }

    private fun datesOf(count: Int, now: Long, maxAhead: Long = 60): List<Long> {
        var last = 0L
        return List(count) { nextSendTime(last, now, maxAhead).also { last = it } }
    }
}
