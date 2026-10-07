package com.bitchat.nostr.util

import kotlin.test.Test
import kotlin.test.assertEquals

class RumorClockTest {
    private var now = 1_000L
    private val clock = RumorClock { now }

    @Test
    fun messagesOfTheSameSecondAreDatedOneSecondApartInOrder() {
        assertEquals(listOf(1_000, 1_001, 1_002, 1_003), List(4) { clock.next() })

        now = 1_010
        assertEquals(1_010, clock.next())
    }

    @Test
    fun theTimeStaysWithinFiveMinutesOfTheClock() {
        val dates = List(400) { clock.next() }

        assertEquals(1_300, dates.max())
        assertEquals((1_000..1_300).toList(), dates.take(301))
    }
}
