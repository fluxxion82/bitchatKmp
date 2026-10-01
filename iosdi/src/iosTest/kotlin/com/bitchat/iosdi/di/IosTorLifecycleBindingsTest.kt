package com.bitchat.iosdi.di

import com.bitchat.domain.app.AppForegroundState
import com.bitchat.domain.base.CoroutineScopeFacade
import com.bitchat.domain.initialization.AppInitializer
import com.bitchat.domain.tor.MutableRequestedTorIntent
import com.bitchat.domain.tor.RequestedTorIntent
import com.bitchat.domain.tor.model.TorMode
import com.bitchat.local.di.commonLocal
import com.bitchat.local.di.localModule
import com.bitchat.local.service.IosAppLifecycleObserver
import com.bitchat.repo.di.commonRepoModule
import com.bitchat.repo.di.repoModule
import com.bitchat.repo.tor.TorLifecycleControl
import com.bitchat.repo.tor.TorLifecycleCoordinator
import com.bitchat.repo.tor.TorLifecycleManager
import com.bitchat.tor.di.torModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.koin.core.context.stopKoin
import org.koin.core.context.startKoin
import org.koin.dsl.module

class IosTorLifecycleBindingsTest {

    @Test
    fun `iOS graph includes the modules that bind lifecycle coordination and observation`() {
        val modules = iosKoinModules(module { })
        assertTrue(modules.contains(commonRepoModule))
        assertTrue(modules.contains(commonLocal))
        assertTrue(modules.contains(localModule))
        assertTrue(modules.contains(torModule))

        val scope = CoroutineScope(SupervisorJob())
        val koin = startKoin {
            modules(
                repoModule,
                localModule,
                module {
                    single<CoroutineScopeFacade> { scopeFacade(scope) }
                    single<RequestedTorIntent> { InMemoryIntent() }
                    single { AppForegroundState() }
                    single<TorLifecycleManager> { NoopLifecycleManager }
                },
            )
        }.koin
        try {
            assertIs<TorLifecycleCoordinator>(koin.get<TorLifecycleControl>())
            assertTrue(koin.getAll<AppInitializer>().any { it is IosAppLifecycleObserver })
        } finally {
            scope.cancel()
            stopKoin()
        }
    }

    private fun scopeFacade(scope: CoroutineScope) = object : CoroutineScopeFacade {
        override val applicationScope: CoroutineScope = scope
        override val connectivityEventScope: CoroutineScope = scope
        override val bluetoothScope: CoroutineScope = scope
        override val nostrScope: CoroutineScope = scope
    }

    private object NoopLifecycleManager : TorLifecycleManager {
        override suspend fun start() = Unit
        override suspend fun stop() = Unit
    }

    private class InMemoryIntent : MutableRequestedTorIntent {
        private val state = MutableStateFlow(TorMode.OFF)
        override val current: TorMode get() = state.value
        override val updates: StateFlow<TorMode> = state
        override fun set(mode: TorMode) {
            state.value = mode
        }
    }
}
