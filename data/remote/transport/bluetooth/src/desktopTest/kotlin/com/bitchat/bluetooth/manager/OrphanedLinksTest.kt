package com.bitchat.bluetooth.manager

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * When the embedded node asks BlueZ to drop a link that nothing here uses.
 *
 * Measured on the boards on 2026-10-08: after an app restart, and after a dial that completed later
 * than gattlib's D-Bus timeout, bluetoothd held a link to the other board while the app broadcast to
 * no devices, and nothing ended that state until the link was dropped by hand.
 */
class OrphanedLinksTest {

    private val grace = 30_000L
    private val board = "52:48:DD:58:29:A7"
    private val phone = "5C:86:8E:4F:BF:4B"
    private val nobody: (String) -> Boolean = { false }

    private fun only(vararg addresses: String): (String) -> Boolean = { it in addresses }

    private fun links() = OrphanedLinks(graceMs = grace)

    private fun OrphanedLinks.dueAt(now: Long, inUse: (String) -> Boolean): List<String> =
        due(now, inUse).map { it.address }

    @Test
    fun aLinkNothingUsesIsDueOnceItHasBeenUpForTheGrace() {
        val links = links()
        links.onLinkUp(board, now = 1_000L)

        // Not before: the other side can still subscribe and write, and a gattlib attempt the
        // reaper has given up on still gets its callback when the services resolve.
        assertEquals(emptyList(), links.dueAt(now = 1_000L + grace - 1, inUse = nobody))
        assertEquals(listOf(board), links.dueAt(now = 1_000L + grace, inUse = nobody))
    }

    @Test
    fun aLinkInUseIsNeverDueHoweverOldItIs() {
        val links = links()
        links.onLinkUp(board, now = 0L)

        assertEquals(emptyList(), links.dueAt(now = 100 * grace, inUse = only(board)))
    }

    @Test
    fun aLinkThatStopsBeingUsedIsDueWithoutAFreshGrace() {
        val links = links()
        links.onLinkUp(board, now = 0L)
        assertEquals(emptyList(), links.dueAt(now = 10 * grace, inUse = only(board)))

        // The app has let go of it and BlueZ still holds it: its age is counted from link-up.
        assertEquals(listOf(board), links.dueAt(now = 10 * grace + 1, inUse = nobody))
    }

    @Test
    fun useIsAskedForOnlyOnceALinkIsOldEnoughToBeDropped() {
        // The caller reads the clock, then this asks about use. So a link is judged only if its
        // grace had already passed when the time was read, and a central that wrote before its
        // deadline is always found in use: its write was registered before the question was put.
        val links = links()
        val asked = mutableListOf<String>()
        links.onLinkUp(board, now = 0L)

        links.due(now = grace - 1) { asked += it; false }
        assertEquals(emptyList(), asked)

        links.due(now = grace) { asked += it; false }
        assertEquals(listOf(board), asked)
    }

    @Test
    fun aLinkThatWentAwayIsForgotten() {
        val links = links()
        links.onLinkUp(board, now = 0L)
        links.onLinkGone(board)

        assertEquals(emptyList(), links.dueAt(now = 10 * grace, inUse = nobody))
        assertEquals(emptySet(), links.tracked())
    }

    @Test
    fun aLinkThatComesBackStartsItsGraceAgain() {
        val links = links()
        links.onLinkUp(board, now = 0L)
        links.onLinkGone(board)
        links.onLinkUp(board, now = 20_000L)

        assertEquals(emptyList(), links.dueAt(now = grace, inUse = nobody))
        assertEquals(listOf(board), links.dueAt(now = 20_000L + grace, inUse = nobody))
    }

    @Test
    fun aSecondReportOfALinkAlreadyKnownDoesNotRestartItsGrace() {
        val links = links()
        links.onLinkUp(board, now = 0L)
        links.onLinkUp(board, now = grace - 1)

        assertEquals(listOf(board), links.dueAt(now = grace, inUse = nobody))
    }

    @Test
    fun aLinkAskedForIsNotAskedForAgainUntilAnotherGraceHasPassed() {
        val links = links()
        links.onLinkUp(board, now = 0L)
        assertEquals(listOf(board), links.dueAt(now = grace, inUse = nobody))

        // The sweep runs every five seconds; BlueZ needs a moment, or refused. Not a storm either way.
        assertEquals(emptyList(), links.dueAt(now = grace + 5_000L, inUse = nobody))
        assertEquals(emptyList(), links.dueAt(now = 2 * grace - 1, inUse = nobody))
        assertEquals(listOf(board), links.dueAt(now = 2 * grace, inUse = nobody))
    }

    @Test
    fun aLinkAskedForIsForgottenWhenItGoesAndIsFreshWhenItReturns() {
        val links = links()
        links.onLinkUp(board, now = 0L)
        assertEquals(listOf(board), links.dueAt(now = grace, inUse = nobody))
        links.onLinkGone(board)
        links.onLinkUp(board, now = grace + 2_000L)

        // The new link is not the one that was asked for: it owes nothing to that request, and it
        // gets its own grace.
        assertEquals(emptyList(), links.dueAt(now = 2 * grace, inUse = nobody))
        assertEquals(listOf(board), links.dueAt(now = 2 * grace + 2_000L, inUse = nobody))
    }

    @Test
    fun eachLinkIsJudgedByItself() {
        val links = links()
        links.onLinkUp(board, now = 0L)
        links.onLinkUp(phone, now = 0L)

        assertEquals(listOf(phone), links.dueAt(now = grace, inUse = only(board)))
        assertEquals(setOf(board, phone), links.tracked())
    }

    @Test
    fun aGoneReportForALinkNeverSeenIsHarmless() {
        val links = links()

        links.onLinkGone(board)

        assertEquals(emptyList(), links.dueAt(now = grace, inUse = nobody))
    }

    @Test
    fun theGraceIsThePolicysConnectDeadlineUnlessSaidOtherwise() {
        val links = OrphanedLinks()
        links.onLinkUp(board, now = 0L)

        val deadline = CentralLinkPolicy.CONNECT_TIMEOUT_MS
        assertEquals(emptyList(), links.dueAt(now = deadline - 1, inUse = nobody))
        assertEquals(listOf(board), links.dueAt(now = deadline, inUse = nobody))
    }

    @Test
    fun reportsFromTheBusThreadAndTheSweepDoNotCorruptTheTable() {
        // Link reports arrive on the D-Bus dispatch thread while the sweep reads from a coroutine.
        val links = links()
        val failure = AtomicReference<Throwable?>(null)
        val start = CountDownLatch(1)
        val rounds = 20_000

        val reporter = thread {
            try {
                start.await()
                repeat(rounds) { round ->
                    val address = "AA:BB:CC:DD:%02X:%02X".format(round % 7, round % 251)
                    links.onLinkUp(address, now = round.toLong())
                    if (round % 3 == 0) links.onLinkGone(address)
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }
        val sweeper = thread {
            try {
                start.await()
                repeat(rounds) { round ->
                    links.due(now = round.toLong() + grace, inUse = nobody)
                    links.tracked()
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }
        start.countDown()
        reporter.join(TimeUnit.SECONDS.toMillis(30))
        sweeper.join(TimeUnit.SECONDS.toMillis(30))

        assertTrue(!reporter.isAlive && !sweeper.isAlive, "the two threads did not finish")
        assertNull(failure.get())
    }

    @Test
    fun lookingWhetherAnythingIsDueRecordsNothing() {
        val links = links()
        links.onLinkUp(board, now = 0L)

        assertFalse(links.anyDue(now = grace - 1, inUse = nobody))
        assertTrue(links.anyDue(now = grace, inUse = nobody))
        assertFalse(links.anyDue(now = grace, inUse = only(board)))

        // The look is for deciding whether to open the bus. Had it counted as asking, the link
        // would not be due now, and would never be asked for at all.
        assertTrue(links.anyDue(now = grace + 1, inUse = nobody))
        assertEquals(listOf(board), links.dueAt(now = grace + 2, inUse = nobody))
        assertFalse(links.anyDue(now = grace + 3, inUse = nobody))
    }

    @Test
    fun aLinkFoundDueIsStillThatLinkUntilItGoes() {
        val links = links()
        links.onLinkUp(board, now = 0L)
        val orphan = links.due(now = grace, inUse = nobody).single()

        assertTrue(links.isSameLink(orphan))
        links.onLinkUp(board, now = grace + 1)
        assertTrue(links.isSameLink(orphan))

        links.onLinkGone(board)
        assertFalse(links.isSameLink(orphan))
    }

    @Test
    fun aLinkThatReplacedTheOneFoundDueIsNotIt() {
        // Between finding a link due and sending the request the bus is waited for. A link that
        // went and came back in that time has had no grace: the request must not be sent for it.
        val links = links()
        links.onLinkUp(board, now = 0L)
        val orphan = links.due(now = grace, inUse = nobody).single()

        links.onLinkGone(board)
        links.onLinkUp(board, now = grace + 2_000L)

        assertFalse(links.isSameLink(orphan))
    }

    @Test
    fun aLinkLeftAloneIsNotJudgedAgainButALaterOneIs() {
        val links = links()
        links.onLinkUp(phone, now = 0L)
        val orphan = links.due(now = grace, inUse = nobody).single()

        // BlueZ shows no bitchat service on it: it is not ours to drop.
        links.leaveAlone(orphan)
        assertEquals(emptyList(), links.dueAt(now = 10 * grace, inUse = nobody))
        assertEquals(emptySet(), links.tracked())

        links.onLinkUp(phone, now = 10 * grace)
        assertEquals(listOf(phone), links.dueAt(now = 11 * grace, inUse = nobody))
    }

    @Test
    fun leavingAloneALinkThatHasBeenReplacedLeavesTheNewOneBe() {
        val links = links()
        links.onLinkUp(phone, now = 0L)
        val orphan = links.due(now = grace, inUse = nobody).single()
        links.onLinkGone(phone)
        links.onLinkUp(phone, now = grace + 1_000L)

        links.leaveAlone(orphan)

        assertEquals(setOf(phone), links.tracked())
        assertEquals(listOf(phone), links.dueAt(now = 2 * grace + 1_000L, inUse = nobody))
    }

    // Which of the links BlueZ holds are a bitchat peer's, and so this app's to disconnect.

    private val service = "F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C"

    @Test
    fun aConnectedDeviceThatShowsTheBitchatServiceIsOurs() {
        // BlueZ reports UUIDs in lower case; the app's constant is upper case.
        val devices = listOf(
            BlueZDeviceInfo(board, connected = true, uuids = listOf("00001800-0000-1000-8000-00805f9b34fb", service.lowercase()))
        )

        assertEquals(listOf(board), bitchatLinks(devices, service))
    }

    @Test
    fun aConnectedDeviceThatShowsNoBitchatServiceIsNotOurs() {
        // Nothing says it is ours: it may be a device another program connected.
        val devices = listOf(
            BlueZDeviceInfo(phone, connected = true, uuids = emptyList()),
            BlueZDeviceInfo(board, connected = true, uuids = listOf("0000180a-0000-1000-8000-00805f9b34fb"))
        )

        assertEquals(emptyList(), bitchatLinks(devices, service))
    }

    @Test
    fun aDeviceBlueZOnlyKnowsOfIsNoLink() {
        val devices = listOf(
            BlueZDeviceInfo(board, connected = false, uuids = listOf(service.lowercase())),
            BlueZDeviceInfo(phone, connected = false, uuids = emptyList())
        )

        assertEquals(emptyList(), bitchatLinks(devices, service))
        assertEquals(emptyList(), bitchatLinks(emptyList(), service))
    }

    @Test
    fun onlyTheBitchatLinksAmongSeveralAreOurs() {
        val devices = listOf(
            BlueZDeviceInfo(phone, connected = true, uuids = emptyList()),
            BlueZDeviceInfo(board, connected = true, uuids = listOf(service)),
            BlueZDeviceInfo("90:82:8D:69:79:2D", connected = false, uuids = listOf(service))
        )

        assertEquals(listOf(board), bitchatLinks(devices, service))
    }
}
