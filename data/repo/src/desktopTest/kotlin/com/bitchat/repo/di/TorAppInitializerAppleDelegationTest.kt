package com.bitchat.repo.di

import com.bitchat.domain.app.AppForegroundState
import com.bitchat.domain.base.CoroutineScopeFacade
import com.bitchat.domain.base.CoroutinesContextFacade
import com.bitchat.domain.base.DefaultScopeFacade
import com.bitchat.domain.base.defaultContextFacade
import com.bitchat.domain.tor.MutableRequestedTorIntent
import com.bitchat.domain.tor.RequestedTorIntent
import com.bitchat.domain.tor.eventbus.InMemoryTorEventBus
import com.bitchat.domain.tor.eventbus.TorEventBus
import com.bitchat.domain.tor.model.TorMode
import com.bitchat.domain.tor.model.TorStatus
import com.bitchat.repo.tor.TorLifecycleControl
import com.bitchat.tor.TorManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

class TorAppInitializerAppleDelegationTest {

    @Test
    fun `cold start reaches the Apple coordinator exactly once when requested ON in foreground`() = runBlocking {
        val fixture = fixture(requested = TorMode.ON, foreground = true)
        try {
            fixture.initializer.initialize()
            fixture.coordinator.reconcile()
            withTimeout(1.seconds) { fixture.started.await() }

            assertEquals(1, fixture.starts.get())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `backgrounded cold start waits for a foreground edge before starting`() = runBlocking {
        val fixture = fixture(requested = TorMode.ON, foreground = false)
        try {
            fixture.initializer.initialize()
            fixture.coordinator.reconcile()

            assertEquals(0, fixture.starts.get())
            fixture.foregroundState.publish(true)
            withTimeout(1.seconds) { fixture.started.await() }

            assertEquals(1, fixture.starts.get())
        } finally {
            fixture.close()
        }
    }

    private fun fixture(requested: TorMode, foreground: Boolean): Fixture {
        val intent = InMemoryIntent(requested)
        val foregroundState = AppForegroundState(foreground)
        val starts = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val manager = mockk<TorManager>(relaxed = true).also {
            every { it.statusFlow } returns MutableStateFlow(TorStatus())
            every { it.isAvailable } returns true
            coEvery { it.start() } coAnswers {
                starts.incrementAndGet()
                started.complete(Unit)
            }
        }
        val koin = startKoin {
            modules(
                commonRepoModule,
                appleTorLifecycleModule,
                module {
                    single<CoroutinesContextFacade> { defaultContextFacade }
                    single<CoroutineScopeFacade> { DefaultScopeFacade(get()) }
                    single<TorEventBus> { InMemoryTorEventBus(get()) }
                    single<MutableRequestedTorIntent> { intent }
                    single<RequestedTorIntent> { get<MutableRequestedTorIntent>() }
                    single { foregroundState }
                    single { manager }
                },
            )
        }.koin

        return Fixture(
            initializer = koin.get(),
            coordinator = koin.get(),
            foregroundState = foregroundState,
            starts = starts,
            started = started,
            scopeFacade = koin.get(),
        )
    }

    private class Fixture(
        val initializer: TorAppInitializer,
        val coordinator: TorLifecycleControl,
        val foregroundState: AppForegroundState,
        val starts: AtomicInteger,
        val started: CompletableDeferred<Unit>,
        private val scopeFacade: CoroutineScopeFacade,
    ) {
        fun close() {
            scopeFacade.applicationScope.cancel()
            stopKoin()
        }
    }

    private class InMemoryIntent(initial: TorMode) : MutableRequestedTorIntent {
        private val state = MutableStateFlow(initial)
        override val current: TorMode get() = state.value
        override val updates: StateFlow<TorMode> = state
        override fun set(mode: TorMode) {
            state.value = mode
        }
    }
}
