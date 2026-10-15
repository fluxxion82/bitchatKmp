package com.bitchat.bluetooth.manager

import com.bitchat.bluetooth.manager.AdvertisementRegistration.Next
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the embedded node does with BlueZ's answer to RegisterAdvertisement.
 *
 * Measured on the Compose board on 2026-10-08: a restart landed while bluetoothd still held the old
 * process's pending connect, BlueZ answered later than the 5 s the app waited, and the app gave the
 * advertisement up; nothing could find the board for the 6.5 minutes that process lived.
 *
 * Seen on the terminal UI board on 2026-10-13: the first start after a deploy was refused with
 * org.bluez.Error.Failed at once, the controller having refused LE Set Extended Advertising Enable
 * twice around the restart, and the next start 48 s later registered in 87 ms.
 */
class AdvertisementRegistrationTest {

    private val noReply = "org.freedesktop.DBus.Error.NoReply"
    private val alreadyExists = "org.bluez.Error.AlreadyExists"
    private val failed = "org.bluez.Error.Failed"
    private val notPermitted = "org.bluez.Error.NotPermitted"
    private val invalidArguments = "org.bluez.Error.InvalidArguments"
    private val notSupported = "org.bluez.Error.NotSupported"

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
    fun aRefusalOtherThanFailedIsFinal() {
        for (refusal in listOf(notPermitted, invalidArguments, notSupported)) {
            val registration = AdvertisementRegistration()
            registration.asked(now = 0L)

            assertEquals(Next.GaveUp(refusal), registration.answered(refusal, now = 10L))
            assertEquals(Next.Ignore, registration.answered(null, now = 20L))
            assertFalse(registration.isAsking)
            assertFalse(registration.isRegistered)
            assertFalse(registration.retryDue(now = 1_000_000L))
        }
    }

    @Test
    fun aFailedAnswerIsAskedForAgainAfterADelayAndTheTimeCountsFromTheFirstRequest() {
        val registration = AdvertisementRegistration()
        registration.asked(now = 1_000L)

        assertEquals(Next.RetryAfter(delayMs = 2_000L), registration.answered(failed, now = 1_010L))
        assertTrue(registration.isAsking)
        assertTrue(registration.isWaitingToRetry)
        assertFalse(registration.isRegistered)

        assertFalse(registration.retryDue(now = 3_009L))
        assertTrue(registration.retryDue(now = 3_010L))
        assertFalse(registration.isWaitingToRetry)
        assertFalse(registration.retryDue(now = 3_011L), "a retry is made once")

        assertEquals(Next.Registered(afterMs = 2_100L, late = false), registration.answered(null, now = 3_100L))
        assertTrue(registration.isRegistered)
    }

    @Test
    fun aFailedAnswerIsRetriedThreeTimesWithGrowingDelaysThenGivenUp() {
        val registration = AdvertisementRegistration()
        registration.asked(now = 0L)
        var now = 0L

        for (delay in listOf(2_000L, 10_000L, 30_000L)) {
            now += 5
            assertEquals(Next.RetryAfter(delay), registration.answered(failed, now))
            assertFalse(registration.retryDue(now + delay - 1))
            now += delay
            assertTrue(registration.retryDue(now))
        }

        now += 5
        assertEquals(Next.GaveUp(failed), registration.answered(failed, now))
        assertFalse(registration.isAsking)
        assertFalse(registration.retryDue(now + 1_000_000L))
        assertEquals(Next.Ignore, registration.answered(null, now + 1))
    }

    @Test
    fun aWaitingRetryIsNotAnAnswerAndIgnoresOne() {
        val registration = AdvertisementRegistration()
        registration.asked(now = 0L)
        registration.answered(failed, now = 5L)

        assertEquals(Next.Ignore, registration.answered(null, now = 100L))
        assertTrue(registration.isAsking)
        assertFalse(registration.isRegistered)
    }

    @Test
    fun startUpStoppingWaitingDuringARetryDelayMakesTheLaterAnswerLate() {
        val registration = AdvertisementRegistration()
        registration.asked(now = 0L)
        registration.answered(failed, now = 5L)

        assertTrue(registration.stopWaiting())
        assertTrue(registration.retryDue(now = 2_005L))
        assertEquals(Next.Registered(afterMs = 2_090L, late = true), registration.answered(null, now = 2_090L))
    }

    @Test
    fun aStopDuringARetryDelayEndsItAndUnregisters() {
        val registration = AdvertisementRegistration()
        registration.asked(now = 0L)
        registration.answered(failed, now = 5L)

        assertTrue(registration.stop())

        assertFalse(registration.isAsking)
        assertFalse(registration.retryDue(now = 1_000_000L))
        assertEquals(Next.Ignore, registration.answered(null, now = 1_000_001L))
    }

    @Test
    fun aRetryThatCouldNotBeSentGivesUp() {
        val registration = AdvertisementRegistration()
        registration.asked(now = 0L)
        registration.answered(failed, now = 5L)
        assertTrue(registration.retryDue(now = 2_005L))

        registration.couldNotAskAgain()

        assertFalse(registration.isAsking)
        assertEquals(Next.Ignore, registration.answered(null, now = 2_006L))
    }

    @Test
    fun aFailedRetryDoesNotUseUpTheBudgetForMissingAnswers() {
        val registration = AdvertisementRegistration(maxRequests = 2)
        registration.asked(now = 0L)

        assertEquals(Next.RetryAfter(2_000L), registration.answered(failed, now = 1L))
        assertTrue(registration.retryDue(now = 2_001L))
        assertEquals(Next.AskAgain, registration.answered(noReply, now = 2_002L))
        assertEquals(Next.GaveUp("no answer to 2 requests"), registration.answered(noReply, now = 2_003L))
    }

    @Test
    fun aFailedAnswerAfterAStopStartIsJudgedAfresh() {
        val registration = AdvertisementRegistration()
        registration.asked(now = 0L)
        var now = 0L
        for (delay in listOf(2_000L, 10_000L, 30_000L)) {
            now += 1
            assertEquals(Next.RetryAfter(delay), registration.answered(failed, now))
            now += delay
            assertTrue(registration.retryDue(now))
        }
        registration.stop()

        registration.asked(now = 100_000L)

        assertEquals(Next.RetryAfter(2_000L), registration.answered(failed, now = 100_010L))
    }

    @Test
    fun theDelaysAreTheOnesGivenAndNoDelayMeansFailedIsFinal() {
        val custom = AdvertisementRegistration(failedRetryDelaysMs = listOf(100L, 200L))
        custom.asked(now = 0L)
        assertEquals(Next.RetryAfter(100L), custom.answered(failed, now = 1L))
        assertTrue(custom.retryDue(now = 101L))
        assertEquals(Next.RetryAfter(200L), custom.answered(failed, now = 102L))
        assertFalse(custom.retryDue(now = 301L))
        assertTrue(custom.retryDue(now = 302L))
        assertEquals(Next.GaveUp(failed), custom.answered(failed, now = 303L))

        val none = AdvertisementRegistration(failedRetryDelaysMs = emptyList())
        none.asked(now = 0L)
        assertEquals(Next.GaveUp(failed), none.answered(failed, now = 1L))
    }

    @Test
    fun aSpanTooLongToCountIsReportedAsTheLongestOne() {
        val registration = AdvertisementRegistration(failedRetryDelaysMs = listOf(Long.MAX_VALUE))
        registration.asked(now = -2L)

        assertEquals(Next.RetryAfter(Long.MAX_VALUE), registration.answered(failed, now = -1L))
        assertTrue(registration.retryDue(now = Long.MAX_VALUE - 1L))
        assertEquals(Next.Registered(afterMs = Long.MAX_VALUE, late = false), registration.answered(null, now = Long.MAX_VALUE))
    }

    @Test
    fun aNegativeDelayIsRefused() {
        assertFailsWith<IllegalArgumentException> { AdvertisementRegistration(failedRetryDelaysMs = listOf(2_000L, -1L)) }
        AdvertisementRegistration(failedRetryDelaysMs = listOf(0L, Long.MAX_VALUE))
    }

    @Test
    fun aDeadlinePastTheLargestClockReadingIsNotDueBeforeIt() {
        val registration = AdvertisementRegistration()
        registration.asked(now = Long.MAX_VALUE - 1_001L)

        assertEquals(Next.RetryAfter(2_000L), registration.answered(failed, now = Long.MAX_VALUE - 1_000L))
        assertFalse(registration.retryDue(now = Long.MAX_VALUE - 999L))
        assertFalse(registration.retryDue(now = Long.MAX_VALUE - 1L))
        assertTrue(registration.retryDue(now = Long.MAX_VALUE))

        val huge = AdvertisementRegistration(failedRetryDelaysMs = listOf(Long.MAX_VALUE))
        huge.asked(now = 1_000L)
        assertEquals(Next.RetryAfter(Long.MAX_VALUE), huge.answered(failed, now = 1_010L))
        assertFalse(huge.retryDue(now = 1_011L))
        assertFalse(huge.retryDue(now = Long.MAX_VALUE - 1L))
    }

    @Test
    fun theDelaysAreCopiedWhenTheRegistrationIsMade() {
        val delays = mutableListOf(2_000L)
        val registration = AdvertisementRegistration(failedRetryDelaysMs = delays)
        delays[0] = Long.MAX_VALUE
        delays += 5L

        registration.asked(now = 1_000L)

        assertEquals(Next.RetryAfter(2_000L), registration.answered(failed, now = 1_010L))
        assertFalse(registration.retryDue(now = 3_009L))
        assertTrue(registration.retryDue(now = 3_010L))
        assertEquals(Next.GaveUp(failed), registration.answered(failed, now = 3_011L))
    }

    @Test
    fun missingAnswersBeforeAFailedAnswerKeepTheirOwnBudget() {
        val registration = AdvertisementRegistration(maxRequests = 3)
        registration.asked(now = 0L)

        assertEquals(Next.AskAgain, registration.answered(noReply, now = 1L))
        assertEquals(Next.RetryAfter(2_000L), registration.answered(failed, now = 2L))
        assertTrue(registration.retryDue(now = 2_002L))
        assertEquals(Next.AskAgain, registration.answered(noReply, now = 2_003L))
        assertEquals(Next.GaveUp("no answer to 3 requests"), registration.answered(noReply, now = 2_004L))
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

        val refused = AdvertisementRegistration().apply { asked(0L); answered(notPermitted, 1L) }
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
