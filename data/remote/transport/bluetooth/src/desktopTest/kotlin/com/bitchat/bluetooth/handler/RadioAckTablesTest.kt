package com.bitchat.bluetooth.handler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** The receiver's notes of what is still to be acknowledged over the radio. */
class RadioAckNotesTest {
    private val clock = SteppedClock()
    private val notes = RadioAckNotes(clock, maxAgeMs = 100)

    @Test
    fun onlyTheNumberThatBeginsAnEntryAsksForASender() {
        assertTrue(notes.note("a", token = 1, number = 10))
        assertFalse(notes.note("a", token = 1, number = 11))
        // Another peer has an entry, and a sender, of its own.
        assertTrue(notes.note("b", token = 7, number = 10))

        assertEquals(listOf(10L, 11L), notes.snapshot("a", 1))
        assertEquals(listOf(10L), notes.snapshot("b", 7))
    }

    @Test
    fun aNumberIsNotedOnce() {
        notes.note("a", 1, 10)
        assertFalse(notes.note("a", 1, 10))

        assertEquals(listOf(10L), notes.snapshot("a", 1))
    }

    @Test
    fun aNinthNumberPushesOutTheOldest() {
        (1L..9L).forEach { notes.note("a", 1, it) }

        assertEquals((2L..9L).toList(), notes.snapshot("a", 1))
    }

    @Test
    fun numbersOfAnotherSessionOfThePeerGoWithTheirEntryAndTheNewOnesAskForASender() {
        notes.note("a", token = 1, number = 10)
        assertTrue(notes.note("a", token = 2, number = 3))

        assertEquals(emptyList(), notes.snapshot("a", 1))
        assertEquals(listOf(3L), notes.snapshot("a", 2))
        assertEquals(1, notes.size)
    }

    @Test
    fun aPeerBeyondTheBoundIsNotNoted() {
        val few = RadioAckNotes(clock, maxAgeMs = 100, maxPeers = 2)
        few.note("a", 1, 1)
        few.note("b", 1, 1)

        assertFalse(few.note("c", 1, 1))
        assertEquals(emptyList(), few.snapshot("c", 1))
        // A peer that has an entry is still noted, and so is a new session of one of them.
        assertFalse(few.note("a", 1, 2))
        assertTrue(few.note("b", 2, 5))
        assertEquals(2, few.size)
    }

    @Test
    fun whatWasNotedTooLongAgoIsDroppedAndAnEmptyEntryIsGone() {
        notes.note("a", 1, 10)
        clock.advance(60)
        notes.note("a", 1, 11)
        clock.advance(41)

        // The first is 101 ms old, the second 41.
        assertEquals(listOf(11L), notes.snapshot("a", 1))
        clock.advance(60)
        assertEquals(emptyList(), notes.snapshot("a", 1))
        assertEquals(0, notes.size)
        // Nothing is left of the entry: the next number begins a new one.
        assertTrue(notes.note("a", 1, 12))
    }

    @Test
    fun aNumberExactlyAsOldAsAllowedStillGoes() {
        notes.note("a", 1, 10)
        clock.advance(100)

        assertEquals(listOf(10L), notes.snapshot("a", 1))
    }

    @Test
    fun whatHasGoneOutIsForgottenAndTheSenderIsToldWhetherMoreIsLeft() {
        notes.note("a", 1, 10)
        notes.note("a", 1, 11)
        notes.note("a", 1, 12)

        // Only what it is given, and it says that more is left.
        assertTrue(notes.sent("a", 1, listOf(10L, 11L)))
        assertEquals(listOf(12L), notes.snapshot("a", 1))
        // The last: the entry is gone with it, and so is its sender.
        assertFalse(notes.sent("a", 1, listOf(12L)))
        assertEquals(0, notes.size)
        assertTrue(notes.note("a", 1, 13))
    }

    @Test
    fun aSenderOfAnotherSessionTakesNothingAway() {
        notes.note("a", token = 2, number = 10)

        assertFalse(notes.sent("a", token = 1, numbers = listOf(10L)))
        notes.drop("a", token = 1)

        assertEquals(listOf(10L), notes.snapshot("a", 2))
        assertFalse(notes.sent("nobody", 1, listOf(10L)))
    }

    @Test
    fun droppingAnEntryTakesAllOfIt() {
        notes.note("a", 1, 10)
        notes.note("a", 1, 11)

        notes.drop("a", 1)

        assertEquals(emptyList(), notes.snapshot("a", 1))
        assertEquals(0, notes.size)
    }
}

/** The sender's record of which message a packet number carried. */
class SentRadioTextsTest {
    private val clock = SteppedClock()

    @Test
    fun aNumberGivesItsMessageOnceAndInTheOrderAsked() {
        val sent = SentRadioTexts(clock)
        sent.remember("a", token = 1, number = 5, messageID = "five")
        sent.remember("a", token = 1, number = 6, messageID = "six")

        assertEquals(listOf("six", "five"), sent.resolve("a", 1, listOf(6L, 9L, 5L, 6L)))
        assertEquals(emptyList(), sent.resolve("a", 1, listOf(5L, 6L)))
        assertEquals(0, sent.size)
    }

    @Test
    fun theSameNumberOfAnotherPeerOrAnotherSessionIsAnotherMessage() {
        val sent = SentRadioTexts(clock)
        sent.remember("a", token = 1, number = 5, messageID = "to a in the first session")
        sent.remember("a", token = 2, number = 5, messageID = "to a in the second session")
        sent.remember("b", token = 1, number = 5, messageID = "to b")

        assertEquals(emptyList(), sent.resolve("c", 1, listOf(5L)))
        assertEquals(emptyList(), sent.resolve("a", 3, listOf(5L)))
        assertEquals(listOf("to a in the second session"), sent.resolve("a", 2, listOf(5L)))
        assertEquals(listOf("to b"), sent.resolve("b", 1, listOf(5L)))
        assertEquals(listOf("to a in the first session"), sent.resolve("a", 1, listOf(5L)))
    }

    @Test
    fun theOldestGoesWhenThereIsNoRoom() {
        val sent = SentRadioTexts(clock, capacity = 2)
        sent.remember("a", 1, 1, "one")
        sent.remember("a", 1, 2, "two")
        sent.remember("a", 1, 3, "three")

        assertEquals(2, sent.size)
        assertEquals(listOf("two", "three"), sent.resolve("a", 1, listOf(1L, 2L, 3L)))
    }

    @Test
    fun whatWasSentTooLongAgoIsNotConfirmed() {
        val sent = SentRadioTexts(clock, maxAgeMs = 100)
        sent.remember("a", 1, 1, "old")
        clock.advance(60)
        sent.remember("a", 1, 2, "young")
        clock.advance(41)

        assertEquals(listOf("young"), sent.resolve("a", 1, listOf(1L, 2L)))
    }

    @Test
    fun aMessageExactlyAsOldAsAllowedIsStillConfirmed() {
        val sent = SentRadioTexts(clock, maxAgeMs = 100)
        sent.remember("a", 1, 1, "one")
        clock.advance(100)

        assertEquals(listOf("one"), sent.resolve("a", 1, listOf(1L)))
    }
}

/** A clock that only goes forward, and only when a test says so. */
private class SteppedClock : TimeSource {
    private var now = 0L

    fun advance(milliseconds: Long) {
        now += milliseconds
    }

    override fun markNow(): TimeMark = object : TimeMark {
        private val start = now
        override fun elapsedNow(): Duration = (now - start).milliseconds
    }
}
