package com.bitchat.bluetooth.manager

import com.bitchat.bluetooth.manager.AdvertisementRegistration.Next
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the embedded node does with BlueZ's answer to RegisterAdvertisement.
 *
 * Measured on the Compose board on 2026-10-08: a restart landed while bluetoothd still held the old
 * process's pending connect, BlueZ answered later than the 5 s the app waited, and the app gave the
 * advertisement up; nothing could find the board for the 6.5 minutes that process lived.
 */
class AdvertisementRegistrationTest {

    private val noReply = "org.freedesktop.DBus.Error.NoReply"
    private val alreadyExists = "org.bluez.Error.AlreadyExists"
    private val failed = "org.bluez.Error.Failed"

    @Test
    fun anAnswerBeforeStartUpStopsWaitingIsOnTime() {
        val registration = AdvertisementRegistration()
        registration.asked(now = 1_000L)

        assertEquals(Next.Registered(afterMs = 86L, late = false), registration.answered(null, now = 1_086L))
        assertTrue(registration.isRegistered)
    }

    @Test
    fun anAnswerAfterStartUpStoppedWaitingIsStillTakenAndSaysHowLongItTook() {
        val registration = AdvertisementRegistration()
        registration.asked(now = 0L)

        assertTrue(registration.stopWaiting())
        assertEquals(Next.Registered(afterMs = 12_345L, late = true), registration.answered(null, now = 12_345L))
        assertTrue(registration.isRegistered)
    }

    @Test
    fun alreadyExistsMeansBlueZHoldsIt() {
        val registration = AdvertisementRegistration()
        registration.asked(now = 0L)

        assertEquals(Next.Registered(afterMs = 5L, late = false), registration.answered(alreadyExists, now = 5L))
    }

    @Test
    fun aMissingAnswerIsAskedForAgainAndTheTimeCountsFromTheFirstRequest() {
        val registration = AdvertisementRegistration()
        registration.asked(now = 0L)

        assertEquals(Next.AskAgain, registration.answered(noReply, now = 100L))
        assertTrue(registration.isAsking)
        assertEquals(Next.Registered(afterMs = 300L, late = false), registration.answered(null, now = 300L))
    }

    @Test
    fun aMissingAnswerIsAskedForAtMostMaxRequestsTimesInAll() {
        val registration = AdvertisementRegistration(maxRequests = 3)
        registration.asked(now = 0L)

        assertEquals(Next.AskAgain, registration.answered(noReply, now = 1L))
        assertEquals(Next.AskAgain, registration.answered(noReply, now = 2L))
        assertEquals(Next.GaveUp("no answer to 3 requests"), registration.answered(noReply, now = 3L))
        assertFalse(registration.isAsking)
        assertFalse(registration.isRegistered)
    }

    @Test
    fun aRefusalIsFinal() {
        val registration = AdvertisementRegistration()
        registration.asked(now = 0L)

        assertEquals(Next.GaveUp(failed), registration.answered(failed, now = 10L))
        assertEquals(Next.Ignore, registration.answered(null, now = 20L))
        assertFalse(registration.isRegistered)
    }

    @Test
    fun aRequestThatCouldNotBeMadeAgainGivesUp() {
        val registration = AdvertisementRegistration()
        registration.asked(now = 0L)
        assertEquals(Next.AskAgain, registration.answered(noReply, now = 1L))

        registration.couldNotAskAgain()

        assertFalse(registration.isAsking)
        assertEquals(Next.Ignore, registration.answered(null, now = 2L))
    }

    @Test
    fun stopWaitingAfterTheAnswerChangesNothing() {
        val registration = AdvertisementRegistration()
        registration.asked(now = 0L)
        registration.answered(null, now = 50L)

        assertFalse(registration.stopWaiting())
        assertTrue(registration.isRegistered)
    }

    @Test
    fun aStopWhileAskingUnregistersAndALaterAnswerIsIgnored() {
        val registration = AdvertisementRegistration()
        registration.asked(now = 0L)

        // BlueZ holds a client from the moment it reads the request, long before it answers.
        assertTrue(registration.stop())
        assertEquals(Next.Ignore, registration.answered(null, now = 20_000L))
        assertFalse(registration.isRegistered)
    }

    @Test
    fun aStopUnregistersWhateverWasAskedForAndNothingWhenNothingWas() {
        assertFalse(AdvertisementRegistration().stop())

        val registered = AdvertisementRegistration().apply { asked(0L); answered(null, 1L) }
        assertTrue(registered.stop())

        val refused = AdvertisementRegistration().apply { asked(0L); answered(failed, 1L) }
        assertTrue(refused.stop())

        val stopped = AdvertisementRegistration().apply { asked(0L); stop() }
        assertFalse(stopped.stop())
    }

    @Test
    fun aStartAfterAStopIsJudgedAfresh() {
        val registration = AdvertisementRegistration(maxRequests = 2)
        registration.asked(now = 0L)
        registration.stopWaiting()
        registration.answered(noReply, now = 1L)
        registration.stop()

        registration.asked(now = 1_000L)

        assertEquals(Next.AskAgain, registration.answered(noReply, now = 1_001L))
        assertEquals(Next.Registered(afterMs = 40L, late = false), registration.answered(null, now = 1_040L))
    }
}
