package com.bitchat.repo.tor

import com.bitchat.domain.app.AppForegroundState
import com.bitchat.domain.tor.MutableRequestedTorIntent
import com.bitchat.domain.tor.model.TorMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class TorLifecycleCoordinatorTest {

    @Test
    fun `cold ON starts exactly once when initializer and foreground seed race`() = runTest {
        val manager = FakeTorLifecycleManager()
        val intent = FakeIntent(TorMode.ON)
        val foreground = AppForegroundState()
        val coordinator = TorLifecycleCoordinator(manager, intent, foreground, backgroundScope)

        coordinator.initialize()
        foreground.publish(true)
        coordinator.reconcile()
        runCurrent()

        assertEquals(1, manager.starts)
        assertEquals(0, manager.stops)
    }

    @Test
    fun `background during bootstrap stops the generation`() = runTest {
        val startRelease = CompletableDeferred<Unit>()
        val manager = FakeTorLifecycleManager(startRelease = startRelease)
        val intent = FakeIntent(TorMode.ON)
        val foreground = AppForegroundState(true)
        val coordinator = TorLifecycleCoordinator(manager, intent, foreground, backgroundScope)

        coordinator.initialize()
        runCurrent()
        manager.awaitStart()
        foreground.publish(false)
        runCurrent()
        startRelease.complete(Unit)
        runCurrent()

        assertEquals(1, manager.starts)
        assertEquals(1, manager.stops)
    }

    @Test
    fun `foreground while start is in flight does not duplicate start`() = runTest {
        val startRelease = CompletableDeferred<Unit>()
        val manager = FakeTorLifecycleManager(startRelease = startRelease)
        val intent = FakeIntent(TorMode.ON)
        val foreground = AppForegroundState(true)
        val coordinator = TorLifecycleCoordinator(manager, intent, foreground, backgroundScope)

        coordinator.initialize()
        runCurrent()
        manager.awaitStart()
        foreground.publish(true)
        runCurrent()
        startRelease.complete(Unit)
        runCurrent()

        assertEquals(1, manager.starts)
        assertEquals(0, manager.stops)
    }

    @Test
    fun `repeated foreground events do not stack restarts`() = runTest {
        val manager = FakeTorLifecycleManager()
        val intent = FakeIntent(TorMode.ON)
        val foreground = AppForegroundState(true)
        val coordinator = TorLifecycleCoordinator(manager, intent, foreground, backgroundScope)

        coordinator.initialize()
        runCurrent()
        foreground.publish(true)
        foreground.publish(true)
        foreground.publish(true)
        runCurrent()

        assertEquals(1, manager.starts)
        assertEquals(0, manager.stops)
    }

    @Test
    fun `foreground after a blocked background stop starts one new generation`() = runTest {
        val stopRelease = CompletableDeferred<Unit>()
        val manager = FakeTorLifecycleManager(stopRelease = stopRelease)
        val intent = FakeIntent(TorMode.ON)
        val foreground = AppForegroundState(true)
        val coordinator = TorLifecycleCoordinator(manager, intent, foreground, backgroundScope)

        coordinator.initialize()
        runCurrent()
        foreground.publish(false)
        runCurrent()
        manager.awaitStop()
        foreground.publish(true)
        runCurrent()
        stopRelease.complete(Unit)
        runCurrent()

        assertEquals(2, manager.starts)
        assertEquals(1, manager.stops)
    }

    @Test
    fun `queued foreground does not restart after the user publishes OFF during a blocked stop`() = runTest {
        val stopRelease = CompletableDeferred<Unit>()
        val manager = FakeTorLifecycleManager(stopRelease = stopRelease)
        val intent = FakeIntent(TorMode.ON)
        val foreground = AppForegroundState(true)
        val coordinator = TorLifecycleCoordinator(manager, intent, foreground, backgroundScope)

        coordinator.initialize()
        runCurrent()
        foreground.publish(false)
        runCurrent()
        manager.awaitStop()

        foreground.publish(true)
        intent.set(TorMode.OFF)
        runCurrent()
        stopRelease.complete(Unit)
        runCurrent()

        assertEquals(1, manager.starts, "no generation starts after OFF was published")
        assertEquals(1, manager.stops)
        assertEquals(false, manager.running)
    }

    @Test
    fun `foreground during a user OFF transition does not resurrect Tor`() = runTest {
        val stopRelease = CompletableDeferred<Unit>()
        val manager = FakeTorLifecycleManager(stopRelease = stopRelease)
        val intent = FakeIntent(TorMode.ON)
        val foreground = AppForegroundState(true)
        val coordinator = TorLifecycleCoordinator(manager, intent, foreground, backgroundScope)

        coordinator.initialize()
        runCurrent()
        intent.set(TorMode.OFF)
        runCurrent()
        manager.awaitStop()
        foreground.publish(true)
        runCurrent()
        stopRelease.complete(Unit)
        runCurrent()

        assertEquals(1, manager.starts)
        assertEquals(1, manager.stops)
    }

    @Test
    fun `requested OFF never touches Tor across foreground changes`() = runTest {
        val manager = FakeTorLifecycleManager()
        val intent = FakeIntent(TorMode.OFF)
        val foreground = AppForegroundState()
        val coordinator = TorLifecycleCoordinator(manager, intent, foreground, backgroundScope)

        coordinator.initialize()
        foreground.publish(true)
        foreground.publish(false)
        foreground.publish(true)
        runCurrent()

        assertEquals(0, manager.starts)
        assertEquals(0, manager.stops)
    }

    @Test
    fun `toggle ON while backgrounded waits for foreground before starting`() = runTest {
        val manager = FakeTorLifecycleManager()
        val intent = FakeIntent(TorMode.OFF)
        val foreground = AppForegroundState(false)
        val coordinator = TorLifecycleCoordinator(manager, intent, foreground, backgroundScope)

        coordinator.initialize()
        intent.set(TorMode.ON)
        runCurrent()

        assertEquals(0, manager.starts, "backgrounded Tor remains stopped to fail closed")
        foreground.publish(true)
        runCurrent()

        assertEquals(1, manager.starts)
    }

    private class FakeIntent(initial: TorMode) : MutableRequestedTorIntent {
        private val state = MutableStateFlow(initial)
        override val current: TorMode get() = state.value
        override val updates: StateFlow<TorMode> = state
        override fun set(mode: TorMode) {
            state.value = mode
        }
    }

    private class FakeTorLifecycleManager(
        private val startRelease: CompletableDeferred<Unit>? = null,
        private val stopRelease: CompletableDeferred<Unit>? = null,
    ) : TorLifecycleManager {
        var starts = 0
        var stops = 0
        var running = false
        private val startEntered = CompletableDeferred<Unit>()
        private val stopEntered = CompletableDeferred<Unit>()

        override suspend fun start() {
            starts += 1
            running = true
            startEntered.complete(Unit)
            startRelease?.await()
        }

        override suspend fun stop() {
            stops += 1
            running = false
            stopEntered.complete(Unit)
            stopRelease?.await()
        }

        suspend fun awaitStart() = withTimeout(1.seconds) { startEntered.await() }
        suspend fun awaitStop() = withTimeout(1.seconds) { stopEntered.await() }
    }
}
