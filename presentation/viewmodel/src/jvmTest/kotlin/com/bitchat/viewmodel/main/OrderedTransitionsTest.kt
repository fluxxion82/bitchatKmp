package com.bitchat.viewmodel.main

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A DM switch selects the peer in the chat repository, suspends, and saves the user state. Cut in
 * between, the peer stays selected while the state says a public chat: that peer's DMs then count
 * as read (unread suppressed, read receipts sent). [OrderedTransitions] never cuts one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OrderedTransitionsTest {
    private var selectedPeer: String? = null
    private var userState = "mesh"

    private suspend fun openBob() {
        selectedPeer = "b0b" // ChatRepo.setSelectedPrivatePeer: assigned before it suspends
        delay(15_000)
        userState = "dm b0b" // the user state, saved later
    }

    @Test fun aSwitchIsFinishedEvenWhenItsScopeIsCancelledHalfway() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val transitions = OrderedTransitions(scope)
        val done = transitions.submit { openBob() }
        advanceTimeBy(1_000)
        assertEquals("b0b", selectedPeer)
        scope.cancel() // the view model is cleared, or a caller gives up, mid-switch
        advanceUntilIdle()
        assertEquals("dm b0b", userState, "never left with the peer selected in a public chat")
        assertTrue(done.isCompleted)
        assertFalse(done.isCancelled)
    }

    @Test fun switchesApplyInTheOrderTheyWereAskedFor() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val transitions = OrderedTransitions(scope)
        val applied = ArrayList<String>()
        transitions.submit { delay(5_000); applied += "open b0b" }
        transitions.submit { applied += "leave b0b" } // asked for second, though quicker
        advanceUntilIdle()
        assertEquals(listOf("open b0b", "leave b0b"), applied)
        scope.cancel()
    }

    @Test fun aFailedSwitchReportsItAndTheNextStillRuns() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val transitions = OrderedTransitions(scope)
        val failed = transitions.submit { error("no such peer") }
        var ran = false
        transitions.submit { ran = true }
        advanceUntilIdle()
        assertTrue(failed.isCancelled, "completed exceptionally")
        assertTrue(ran)
        scope.cancel()
    }
}
