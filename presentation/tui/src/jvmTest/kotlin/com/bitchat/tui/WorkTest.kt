package com.bitchat.tui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class WorkTest {
    @Test fun anOldStopNeverRunsAfterANewerStart() = runTest {
        val workers = CoroutineScope(coroutineContext + Job())
        val applied = ArrayList<String>()
        var running = 0
        var overlapped = false
        val sampler = LatestOnly<List<String>>(workers) { set ->
            running++
            if (running > 1) overlapped = true
            delay(if (set.isEmpty()) 500 else 50) // an "end" slower than a "begin", as on an IO thread
            applied += if (set.isEmpty()) "end" else "begin $set"
            running--
        }
        sampler.request(listOf("9q8yy")) // first visit
        advanceUntilIdle()
        sampler.request(emptyList()) // it closes: end, which is slow
        sampler.request(listOf("dr5ru")) // the next visit opens at once
        advanceUntilIdle()
        assertEquals("begin [dr5ru]", applied.last(), "$applied")
        assertEquals(false, overlapped)
        workers.cancel()
    }

    @Test fun manyRequestsWhileBusyCollapseToTheLatest() = runTest {
        val workers = CoroutineScope(coroutineContext + Job())
        val applied = ArrayList<Int>()
        val gate = CompletableDeferred<Unit>()
        val worker = LatestOnly<Int>(workers) { value ->
            if (value == 0) gate.await()
            applied += value
        }
        worker.request(0)
        advanceUntilIdle()
        for (value in 1..5) worker.request(value)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(0, 5), applied)
        workers.cancel()
    }

    @Test fun trackedWorkIsAwaitedBeforeItsScopeGoes() = runTest {
        val viewModelScope = SupervisorJob()
        val scope = CoroutineScope(viewModelScope + StandardTestDispatcher(testScheduler))
        val longLived = scope.launch { delay(Long.MAX_VALUE) } // a poll, started before: not waited for
        val work = LaunchedWork { viewModelScope }
        var saved = false
        work.track { scope.launch { delay(2_000); saved = true } } // a selection made as the screen closes
        var cleared = false
        launch {
            work.awaitAll()
            cleared = true
            viewModelScope.cancel()
        }
        advanceUntilIdle()
        assertTrue(saved, "the selection finished")
        assertTrue(cleared)
        assertTrue(longLived.isCancelled)
    }
}
