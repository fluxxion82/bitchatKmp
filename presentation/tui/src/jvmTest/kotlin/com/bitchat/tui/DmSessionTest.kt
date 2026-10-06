package com.bitchat.tui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * [DmSession] against a view model stand-in whose requests finish when the test says so, and
 * whose `selectedPrivatePeer` reports arrive when the test says so (conflated, possibly late).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DmSessionTest {
    private val navigation = TuiNavigation(Mode.Peers)
    private val calls = ArrayList<String>()
    private val bob = PeerEntry("b0b", "bob", PeerTransport.Direct)
    private val dora = PeerEntry("d0ra00112233445566778899aabbccdd", "dora", PeerTransport.Nostr)

    /** Requests the stand-in has not finished yet, oldest first; each finishes with the given result. */
    private val unfinished = ArrayDeque<CompletableDeferred<Boolean>>()

    /** When false, requests finish only through [finish]. */
    private var finishAtOnce = true

    /** The keys every leave was asked for, in order. */
    private val leftKeys = ArrayList<String>()

    private fun TestScope.session(
        start: suspend (PeerEntry) -> Boolean = { peer -> calls += "start ${peer.id}"; request() },
        leave: suspend (String) -> Unit = { key -> calls += "leave"; leftKeys += key; request() },
    ): Pair<DmSession, CoroutineScope> {
        val ui = CoroutineScope(coroutineContext + Job())
        val session = DmSession(
            navigation, ui,
            start = start,
            leave = leave,
            describe = { key -> PeerEntry(key, names[key] ?: "who-$key", PeerTransport.Direct, claims = claims[key]) },
            openTimeout = 10.seconds,
            requestTimeout = 10.seconds,
            commandWindow = 10.seconds,
        )
        return session to ui
    }

    private val names = mapOf("b0b" to "bob", "a11ce" to "alice#1f2e", "m4llory" to "mallory", "c4rol" to "carol#c4r0")

    /** What a peer whose DM is shown under its chat's own name announces now, when that is another name. */
    private val claims = mapOf("c4rol" to "caroline")

    private suspend fun request(): Boolean {
        if (finishAtOnce) return true
        val done = CompletableDeferred<Boolean>()
        unfinished.addLast(done)
        try {
            return done.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            calls += "cancelled"
            unfinished.remove(done)
            throw e
        }
    }

    private fun TestScope.finish(ok: Boolean = true) {
        unfinished.removeFirst().complete(ok)
        runCurrent()
    }

    @Test fun aDmIsSendableOnlyOnceItsStartHasFinishedAndTheViewModelShowsIt() = runTest {
        finishAtOnce = false
        val (session, ui) = session()
        session.open(bob)
        runCurrent()
        assertEquals(Mode.Dm, navigation.mode)
        assertEquals(DmPhase.Opening(bob, "b0b", 1), session.phase)
        assertEquals("opening... ", session.sendHold)

        session.onSelectedPeer("b0b") // a report before the start finished: could be stale
        assertEquals(DmPhase.Opening(bob, "b0b", 1), session.phase)
        finish() // the acknowledgement: the view model already shows it, so it is open at once
        assertEquals(DmPhase.Open(bob, "b0b", 1), session.phase)
        assertTrue(session.canSend("b0b"))
        assertFalse(session.canSend(null), "the view model moved on since")
        assertNull(session.sendHold)
        assertEquals(listOf("start b0b"), calls)
        ui.cancel()
    }

    @Test fun afterTheAcknowledgementTheReportOpensIt() = runTest {
        val (session, ui) = session()
        session.open(dora)
        runCurrent()
        assertEquals(DmPhase.Opening(dora, "nostr_d0ra001122334455", 1), session.phase)
        session.onSelectedPeer("nostr_d0ra001122334455")
        assertEquals(DmPhase.Open(dora, "nostr_d0ra001122334455", 1), session.phase)
        ui.cancel()
    }

    @Test fun closingAndReopeningTheSameDmWithNoReportInBetweenOpensIt() = runTest {
        val (session, ui) = session()
        session.open(bob)
        runCurrent()
        session.onSelectedPeer("b0b")
        navigation.mode = Mode.Peers // Esc
        session.open(bob) // at once: the public chat in between is conflated away, never reported
        runCurrent()
        assertEquals(listOf("start b0b", "leave", "start b0b"), calls)
        assertEquals(DmPhase.Open(bob, "b0b", 2), session.phase)
        ui.cancel()
    }

    @Test fun aReopenWaitsForTheLeaveBeforeIt() = runTest {
        val (session, ui) = session()
        session.open(bob)
        runCurrent()
        session.onSelectedPeer("b0b")
        finishAtOnce = false
        navigation.mode = Mode.Peers
        runCurrent()
        session.open(bob)
        runCurrent()
        assertEquals(listOf("start b0b", "leave"), calls, "the second start waits for the leave")
        finish() // the leave, however slow
        assertEquals(listOf("start b0b", "leave", "start b0b"), calls)
        session.onSelectedPeer(null) // the leave's report, late
        finish() // the start
        assertEquals(DmPhase.Opening(bob, "b0b", 2), session.phase)
        session.onSelectedPeer("b0b")
        assertEquals(DmPhase.Open(bob, "b0b", 2), session.phase)
        ui.cancel()
    }

    @Test fun escWhileOpeningLeavesAfterTheStartAndNeverReopens() = runTest {
        finishAtOnce = false
        val (session, ui) = session()
        session.open(bob)
        runCurrent()
        navigation.mode = Mode.Peers
        assertEquals(DmPhase.Closed, session.phase)
        finish() // the start lands after the user left
        session.onSelectedPeer("b0b")
        finish() // the leave queued behind it
        assertEquals(listOf("start b0b", "leave"), calls)
        assertEquals(Mode.Peers, session.modeForChat(navigation.mode))
        session.onSelectedPeer(null)
        assertEquals(listOf("start b0b", "leave"), calls)
        ui.cancel()
    }

    @Test fun aStartThatNeverFinishesIsGivenUpOnAndShownAsFailed() = runTest {
        finishAtOnce = false
        val (session, ui) = session()
        session.open(bob)
        runCurrent()
        advanceTimeBy(10.seconds + 1.seconds)
        assertEquals(listOf("start b0b"), calls, "given up on, but never cancelled")
        assertEquals(DmPhase.Failed(bob, "b0b", 1), session.phase)
        assertEquals("Couldn't open the DM with bob", session.error)
        assertEquals("not open ", session.sendHold)
        assertEquals(Mode.Dm, session.modeForChat(Mode.Dm), "the error stays in view")
        session.onSelectedPeer("b0b") // nothing it did can open it now
        assertEquals(DmPhase.Failed(bob, "b0b", 1), session.phase)
        ui.cancel()
    }

    /**
     * The view model's own ordering, as `OrderedTransitions` gives it: switches apply one after
     * another, each to its end. A switch to a DM selects the peer, suspends, then saves the state.
     */
    private var vmSelectedPeer: String? = null
    private var vmState = "mesh"
    private val vmLock = kotlinx.coroutines.sync.Mutex()

    private var slowStarts = 1

    /** The first start is slow (past the session's 10 s), later ones quick. */
    private val slowHalfCommittingStart: suspend (PeerEntry) -> Boolean = { peer ->
        calls += "start ${peer.id}"
        vmLock.lock()
        try {
            vmSelectedPeer = peer.id // committed before it suspends
            kotlinx.coroutines.delay(if (slowStarts-- > 0) 15.seconds else 1.seconds)
            vmState = "dm ${peer.id}"
        } finally {
            vmLock.unlock()
        }
        true
    }

    private val orderedLeave: suspend (String) -> Unit = { key ->
        calls += "leave $key"
        vmLock.lock()
        try {
            if (vmState == "dm $key") {
                vmState = "mesh"
                vmSelectedPeer = null
            } else if (vmSelectedPeer == key) {
                vmSelectedPeer = null // repair: never the peer selected in a public chat
            }
        } finally {
            vmLock.unlock()
        }
    }

    /** Whether a DM from [peer] arriving now would count as read (unread suppressed, read receipt sent). */
    private fun countedAsRead(peer: String) = vmSelectedPeer == peer

    @Test fun aSlowSwitchIsNeverCutAndIsUndoneOnceItLandsUnwanted() = runTest {
        val (session, ui) = session(slowHalfCommittingStart, orderedLeave)
        session.open(bob)
        runCurrent()
        advanceTimeBy(10.seconds + 1.seconds)
        assertEquals(DmPhase.Failed(bob, "b0b", 1), session.phase)
        assertEquals("b0b", vmSelectedPeer, "half applied, and still running: not cut")
        advanceTimeBy(5.seconds) // the switch lands; the session undoes it
        runCurrent()
        assertEquals(listOf("start b0b", "leave b0b"), calls)
        assertEquals("mesh", vmState)
        assertNull(vmSelectedPeer)
        assertFalse(countedAsRead("b0b"))
        ui.cancel()
    }

    @Test fun leavingWhileASlowSwitchRunsLeavesAConsistentPublicChat() = runTest {
        val (session, ui) = session(slowHalfCommittingStart, orderedLeave)
        session.open(bob)
        runCurrent()
        advanceTimeBy(11.seconds)
        navigation.mode = Mode.Peers // Esc on the error: a leave, applied after the switch
        runCurrent()
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals("mesh", vmState)
        assertNull(vmSelectedPeer)
        assertFalse(countedAsRead("b0b"))
        assertEquals(DmPhase.Closed, session.phase)
        ui.cancel()
    }

    @Test fun aSlowSwitchToADmWantedAgainIsKept() = runTest {
        val (session, ui) = session(slowHalfCommittingStart, orderedLeave)
        session.open(bob)
        runCurrent()
        advanceTimeBy(11.seconds)
        navigation.mode = Mode.Peers
        session.open(bob) // opened again before the first switch landed
        runCurrent()
        advanceTimeBy(6.seconds) // the slow switch lands, then the leave, then the quick second switch
        runCurrent()
        session.onSelectedPeer("b0b")
        assertEquals(DmPhase.Open(bob, "b0b", 2), session.phase)
        advanceTimeBy(40.seconds)
        runCurrent()
        assertEquals(listOf("start b0b", "leave b0b", "start b0b"), calls, "the late switch was not undone: the DM is wanted again")
        assertEquals("dm b0b", vmState)
        assertEquals(DmPhase.Open(bob, "b0b", 2), session.phase)
        ui.cancel()
    }

    @Test fun aStuckStartDoesNotHoldTheLeaveAndReopenBehindIt() = runTest {
        finishAtOnce = false
        val (session, ui) = session()
        session.open(bob) // the view model never finishes this one
        runCurrent()
        navigation.mode = Mode.Peers // Esc: a leave queued behind it
        session.open(bob) // and open it again: a start queued behind that
        runCurrent()
        assertEquals(listOf("start b0b"), calls)
        finishAtOnce = true
        advanceTimeBy(10.seconds + 1.seconds) // the stuck start times out
        assertEquals(listOf("start b0b", "leave", "start b0b"), calls, "the stuck start is left running, not cancelled")
        session.onSelectedPeer("b0b")
        assertEquals(DmPhase.Open(bob, "b0b", 2), session.phase)
        ui.cancel()
    }

    @Test fun aStuckLeaveDoesNotHoldTheQueue() = runTest {
        val (session, ui) = session()
        session.open(bob)
        runCurrent()
        session.onSelectedPeer("b0b")
        finishAtOnce = false
        navigation.mode = Mode.Peers // this leave never finishes
        runCurrent()
        finishAtOnce = true
        session.open(dora)
        runCurrent()
        assertEquals(listOf("start b0b", "leave"), calls)
        advanceTimeBy(10.seconds + 1.seconds)
        assertEquals(listOf("start b0b", "leave", "start ${dora.id}"), calls)
        ui.cancel()
    }

    @Test fun aSwitchNeverReportedFailsAfterTheOpenTimeout() = runTest {
        val (session, ui) = session()
        session.open(bob)
        runCurrent() // the start finished; the view model never reports the DM
        advanceTimeBy(10.seconds + 1.seconds)
        assertEquals(DmPhase.Failed(bob, "b0b", 1), session.phase)
        ui.cancel()
    }

    @Test fun aStartThatFailsFailsAtOnce() = runTest {
        finishAtOnce = false
        val (session, ui) = session()
        session.open(bob)
        runCurrent()
        finish(ok = false)
        assertEquals(DmPhase.Failed(bob, "b0b", 1), session.phase)
        finishAtOnce = true
        navigation.mode = Mode.Peers
        runCurrent()
        assertEquals(listOf("start b0b", "leave"), calls, "a DM-only leave, harmless when nothing was switched to")
        ui.cancel()
    }

    @Test fun theDmRestoredAtStartupIsLeftOnce() = runTest {
        val (session, ui) = session()
        session.onSelectedPeer(null)
        session.onSelectedPeer("b0b")
        runCurrent()
        assertEquals(listOf("leave"), calls)
        assertEquals(Mode.Chat, session.modeForChat(Mode.Chat))
        session.onSelectedPeer(null)
        session.onSelectedPeer("b0b") // a lagging report later on: not acted on
        runCurrent()
        assertEquals(listOf("leave"), calls)
        ui.cancel()
    }

    @Test fun aDmOpenedByACommandIsAdoptedAndSendable() = runTest {
        val (session, ui) = session()
        navigation.mode = Mode.Chat
        session.onChatLine("/msg bob hi")
        session.onSelectedPeer("b0b")
        assertEquals(DmPhase.Open(PeerEntry("b0b", "bob", PeerTransport.Direct), "b0b", null), session.phase)
        assertEquals(Mode.Dm, navigation.mode)
        assertTrue(session.canSend("b0b"))
        navigation.mode = Mode.Peers
        runCurrent()
        assertEquals(listOf("leave"), calls)
        ui.cancel()
    }

    @Test fun aDmFoundByWhatItsPeerAnnouncesNowIsAdopted() = runTest {
        // `/msg caroline` finds the peer by its announcement; its DM is shown under the chat's own name.
        val (session, ui) = session()
        navigation.mode = Mode.Chat
        session.onChatLine("/msg caroline hi")
        session.onSelectedPeer("c4rol")
        assertEquals(
            DmPhase.Open(PeerEntry("c4rol", "carol#c4r0", PeerTransport.Direct, claims = "caroline"), "c4rol", null),
            session.phase,
        )
        assertEquals(Mode.Dm, navigation.mode)
        ui.cancel()
    }

    @Test fun overlappingCommandsAdoptTheDmTheViewModelEndsIn() = runTest {
        val (session, ui) = session()
        navigation.mode = Mode.Chat
        session.onChatLine("/msg bob")
        session.onChatLine("/m @alice hello")
        session.onSelectedPeer("b0b")
        assertEquals("b0b", (session.phase as DmPhase.Open).key)
        session.onSelectedPeer("a11ce") // alice#1f2e: the second command lands
        assertEquals(DmPhase.Open(PeerEntry("a11ce", "alice#1f2e", PeerTransport.Direct), "a11ce", null), session.phase)
        assertTrue(session.canSend("a11ce"))
        ui.cancel()
    }

    @Test fun onlyTheDmACommandNamedIsAdopted() = runTest {
        val (session, ui) = session()
        navigation.mode = Mode.Chat
        session.onChatLine("/msg bob")
        session.onChatLine("/who")
        session.onSelectedPeer("m4llory")
        assertEquals(DmPhase.Closed, session.phase)
        assertEquals(Mode.Chat, navigation.mode)
        runCurrent()
        assertEquals(emptyList<String>(), calls, "not ours to leave either")
        ui.cancel()
    }

    @Test fun aCommandOnlyCountsForAWhile() = runTest {
        val (session, ui) = session()
        session.onChatLine("/msg bob")
        advanceTimeBy(10.seconds + 1.seconds)
        session.onSelectedPeer("b0b")
        assertEquals(DmPhase.Closed, session.phase)
        ui.cancel()
    }

    @Test fun aReportWhileRequestsArePendingIsJudgedWhenTheyFinish() = runTest {
        val (session, ui) = session()
        session.open(dora)
        runCurrent()
        session.onSelectedPeer(dmConversationKey(dora))
        finishAtOnce = false
        navigation.mode = Mode.Chat // leave dora; the leave is slow
        runCurrent()
        session.onChatLine("/msg bob")
        session.onSelectedPeer("b0b") // reported while our leave is pending: not judged yet
        assertEquals(DmPhase.Closed, session.phase)
        finish()
        assertEquals(DmPhase.Open(PeerEntry("b0b", "bob", PeerTransport.Direct), "b0b", null), session.phase)
        ui.cancel()
    }

    @Test fun withoutACommandALaterDmIsNeitherAdoptedNorLeft() = runTest {
        val (session, ui) = session()
        session.open(bob)
        runCurrent()
        session.onSelectedPeer("b0b")
        navigation.mode = Mode.Peers
        runCurrent()
        session.onSelectedPeer("m4llory")
        runCurrent()
        assertEquals(DmPhase.Closed, session.phase)
        assertEquals(listOf("start b0b", "leave"), calls)
        ui.cancel()
    }

    @Test fun anOpenDmTheViewModelMovesAwayFromHoldsSends() = runTest {
        val (session, ui) = session()
        session.open(bob)
        runCurrent()
        session.onSelectedPeer("b0b")
        session.onSelectedPeer(null)
        assertEquals(DmPhase.Open(bob, "b0b", 1), session.phase)
        assertFalse(session.canSend(null))
        assertEquals("waiting... ", session.sendHold)
        ui.cancel()
    }

    @Test fun conversationKeys() {
        assertEquals("b0b", dmConversationKey(bob))
        assertEquals("nostr_d0ra001122334455", dmConversationKey(dora))
        assertEquals("!a1b2c3d4", dmConversationKey("!a1b2c3d4", PeerTransport.LoRa))
    }
}
