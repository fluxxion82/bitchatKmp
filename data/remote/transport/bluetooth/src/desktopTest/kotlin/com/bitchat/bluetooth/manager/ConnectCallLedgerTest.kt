package com.bitchat.bluetooth.manager

import com.bitchat.bluetooth.manager.ConnectCall.BLUEZ_ANSWERED
import com.bitchat.bluetooth.manager.ConnectCall.BUS_REFUSED
import com.bitchat.bluetooth.manager.ConnectCall.OTHER
import com.bitchat.bluetooth.manager.ConnectCall.TIMED_OUT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The counts behind the status line, and when the app gives up and lets systemd restart it: a run of
 * at least three dials the bus refused, the first of them at least a minute old, ended only by an
 * answer from bluetoothd itself.
 */
class ConnectCallLedgerTest {

    private val minute = ConnectCallLedger.EXIT_AFTER_MS

    private fun ledgerWithADueRun(): ConnectCallLedger = ConnectCallLedger().apply {
        onDial(BUS_REFUSED, now = 0L)
        onDial(BUS_REFUSED, now = 1_000L)
        onDial(BUS_REFUSED, now = 2_000L)
    }

    @Test
    fun theNumbers() {
        assertEquals(3, ConnectCallLedger.EXIT_AFTER_REFUSALS)
        assertEquals(60_000L, ConnectCallLedger.EXIT_AFTER_MS)
        assertEquals(30_000L, ConnectCallLedger.SILENT_BLUEZ_REST_MS)
    }

    @Test
    fun nothingIsDueAtTheStart() {
        val ledger = ConnectCallLedger()

        assertFalse(ledger.exitDue(now = 0L))
        assertFalse(ledger.exitDue(now = 10 * minute))
        assertEquals(0, ledger.timedOut)
        assertEquals(0, ledger.busRefused)
    }

    @Test
    fun threeRefusalsSpanningAMinuteMakeTheExitDue() {
        val ledger = ConnectCallLedger()

        ledger.onDial(BUS_REFUSED, now = 1_000L)
        ledger.onDial(BUS_REFUSED, now = 30_000L)
        ledger.onDial(BUS_REFUSED, now = 1_000L + minute)

        assertTrue(ledger.exitDue(now = 1_000L + minute))
    }

    @Test
    fun theMinuteCountsFromTheFirstRefusalOfTheRunAndIsInclusive() {
        val ledger = ConnectCallLedger()

        ledger.onDial(BUS_REFUSED, now = 1_000L)
        ledger.onDial(BUS_REFUSED, now = 2_000L)
        ledger.onDial(BUS_REFUSED, now = 3_000L)

        assertFalse(ledger.exitDue(now = 1_000L + minute - 1))
        assertTrue(ledger.exitDue(now = 1_000L + minute))
    }

    @Test
    fun aRunWhoseDialsStopStillComesDue() {
        val ledger = ledgerWithADueRun()

        assertFalse(ledger.exitDue(now = 2_000L))
        assertTrue(ledger.exitDue(now = 10 * minute))
    }

    @Test
    fun twoRefusalsAreNotEnoughHoweverLongTheyLast() {
        val ledger = ConnectCallLedger()

        ledger.onDial(BUS_REFUSED, now = 0L)
        ledger.onDial(BUS_REFUSED, now = 10 * minute)

        assertFalse(ledger.exitDue(now = 20 * minute))

        ledger.onDial(BUS_REFUSED, now = 20 * minute)

        assertTrue(ledger.exitDue(now = 20 * minute))
    }

    @Test
    fun onlyAnAnswerFromBluezEndsTheRun() {
        val ledger = ConnectCallLedger()

        ledger.onDial(BUS_REFUSED, now = 0L)
        ledger.onDial(BUS_REFUSED, now = 1_000L)
        ledger.onDial(BLUEZ_ANSWERED, now = 2_000L)
        ledger.onDial(BUS_REFUSED, now = 3_000L)
        ledger.onDial(BUS_REFUSED, now = 4_000L)

        assertFalse(ledger.exitDue(now = 3_000L + minute - 1))

        ledger.onDial(BUS_REFUSED, now = 5_000L)

        assertFalse(ledger.exitDue(now = 3_000L + minute - 1))
        assertTrue(ledger.exitDue(now = 3_000L + minute))
    }

    @Test
    fun aTimeOutOrAnythingElseNeitherEndsNorExtendsTheRun() {
        listOf(TIMED_OUT, OTHER).forEach { neither ->
            val ledger = ConnectCallLedger()

            ledger.onDial(BUS_REFUSED, now = 0L)
            ledger.onDial(neither, now = 1_000L)
            ledger.onDial(BUS_REFUSED, now = 2_000L)
            ledger.onDial(neither, now = 3_000L)
            ledger.onDial(neither, now = 4_000L)

            assertFalse(ledger.exitDue(now = minute), "$neither")

            ledger.onDial(BUS_REFUSED, now = 5_000L)

            assertTrue(ledger.exitDue(now = minute), "$neither")
        }
    }

    @Test
    fun aRunAfterAnEndedOneStartsItsOwnMinute() {
        val ledger = ConnectCallLedger()

        repeat(5) { ledger.onDial(BUS_REFUSED, now = it * 1_000L) }
        ledger.onDial(BLUEZ_ANSWERED, now = 10 * minute)
        ledger.onDial(BUS_REFUSED, now = 11 * minute)
        ledger.onDial(BUS_REFUSED, now = 11 * minute + 1)
        ledger.onDial(BUS_REFUSED, now = 11 * minute + 2)

        assertFalse(ledger.exitDue(now = 12 * minute - 1))
        assertTrue(ledger.exitDue(now = 12 * minute))
    }

    @Test
    fun aSilentBluezHoldsTheExitBackForItsRestOnly() {
        val ledger = ledgerWithADueRun()

        ledger.onBluezSilent(now = 2 * minute)

        assertFalse(ledger.exitDue(now = 2 * minute))
        assertFalse(ledger.exitDue(now = 2 * minute + ConnectCallLedger.SILENT_BLUEZ_REST_MS - 1))
        assertTrue(ledger.exitDue(now = 2 * minute + ConnectCallLedger.SILENT_BLUEZ_REST_MS))
    }

    @Test
    fun aSilentBluezDoesNotEndTheRunOrStartANewOne() {
        val ledger = ledgerWithADueRun()
        val rest = ConnectCallLedger.SILENT_BLUEZ_REST_MS

        ledger.onBluezSilent(now = 2 * minute)
        ledger.onDial(BUS_REFUSED, now = 2 * minute + 1)

        assertTrue(ledger.exitDue(now = 2 * minute + rest))
    }

    @Test
    fun aBluezThatStaysSilentIsAskedAgainAfterEveryRest() {
        val ledger = ledgerWithADueRun()
        val rest = ConnectCallLedger.SILENT_BLUEZ_REST_MS

        assertTrue(ledger.exitDue(now = minute))
        ledger.onBluezSilent(now = minute)
        assertFalse(ledger.exitDue(now = minute + rest - 1))
        assertTrue(ledger.exitDue(now = minute + rest))
        ledger.onBluezSilent(now = minute + rest)
        assertFalse(ledger.exitDue(now = minute + 2 * rest - 1))
        assertTrue(ledger.exitDue(now = minute + 2 * rest))
    }

    @Test
    fun theCountsAreSinceTheStartAndNeverReset() {
        val ledger = ConnectCallLedger()

        ledger.onDial(TIMED_OUT, now = 0L)
        ledger.onDial(BUS_REFUSED, now = 1L)
        ledger.onDial(BLUEZ_ANSWERED, now = 2L)
        ledger.onDial(TIMED_OUT, now = 3L)
        ledger.onDial(OTHER, now = 4L)
        ledger.onDial(BUS_REFUSED, now = 5L)
        ledger.onBluezSilent(now = 6L)
        ledger.onDial(BUS_REFUSED, now = 7L)

        assertEquals(2, ledger.timedOut)
        assertEquals(3, ledger.busRefused)
    }

    @Test
    fun theStatusLineFields() {
        assertEquals(
            " timed-out=4 released=3 bus-refused=2",
            connectCallsText(timedOut = 4, released = 3, busRefused = 2)
        )
    }
}
