package com.bitchat.nostr

import com.bitchat.client.RouteAwareClientProvider
import com.bitchat.client.di.clientModule
import com.bitchat.domain.initialization.models.AppInformation
import com.bitchat.domain.initialization.models.Version
import com.bitchat.domain.tor.RequestedTorIntent
import com.bitchat.domain.tor.model.TorMode
import com.bitchat.domain.tor.model.TorState
import com.bitchat.domain.tor.model.TorStatus
import com.bitchat.nostr.di.nostrModule
import com.bitchat.transport.IdentityStoreState
import com.bitchat.transport.TransportIdentityProvider
import com.bitchat.tor.TorManager
import com.bitchat.tor.TorManagerRouteSource
import com.bitchat.tor.TorRouteSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TorStartupKoinGraphTest {
    @AfterTest
    fun stopKoinAfterEachTest() = stopKoin()

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `constructing real Nostr graph waits for Tor only when opening a route`() = runTest {
        val status = MutableStateFlow(TorStatus(state = TorState.STARTING, mode = TorMode.ON))
        var ready = false
        val torManager = mockk<TorManager>()
        every { torManager.statusFlow } returns status
        every { torManager.isAvailable } returns true
        every { torManager.isProxyReady() } answers { ready }
        every { torManager.getSocksProxyAddress() } answers { if (ready) "127.0.0.1" to 9050 else null }

        val koin = startKoin {
            modules(
                clientModule,
                nostrModule,
                module {
                    single { AppInformation(Version("test", "test", ""), 1, "test", debug = false) }
                    single<RequestedTorIntent> { RequestedIntent(TorMode.ON) }
                    single { torManager }
                    single<TorRouteSource> { TorManagerRouteSource(torManager) }
                    single<NostrPreferences> { TestNostrPreferences() }
                    single<TransportIdentityProvider> { TestIdentityProvider() }
                },
            )
        }.koin

        koin.get<NostrTransport>()
        verify(exactly = 0) { torManager.getSocksProxyAddress() }

        val provider = koin.get<RouteAwareClientProvider>()
        val opening = async { provider.openWebSocket() }
        runCurrent()
        assertFalse(opening.isCompleted)

        ready = true
        status.value = TorStatus(state = TorState.RUNNING, mode = TorMode.ON, running = true, routeGeneration = 1)

        opening.await().close()
        assertTrue(ready)
    }

    private class RequestedIntent(override val current: TorMode) : RequestedTorIntent {
        override val updates: StateFlow<TorMode> = MutableStateFlow(current)
    }

    private class TestNostrPreferences : NostrPreferences {
        override fun getLastUpdateMs() = 0L
        override fun setLastUpdateMs(value: Long) = Unit
        override fun setPowEnabled(enabled: Boolean) = Unit
        override fun getPowEnabled() = false
        override fun setPowDifficulty(difficulty: Int) = Unit
        override fun getPowDifficulty() = 0
        override fun setIsMining(isMining: Boolean) = Unit
        override fun getIsMiningFlow() = MutableStateFlow(false)
    }

    private class TestIdentityProvider : TransportIdentityProvider {
        override fun loadKey(key: String): String? = null
        override fun saveKey(key: String, value: String) = Unit
        override fun hasKey(key: String) = false
        override fun removeKeys(vararg keys: String) = Unit
        override fun clearAll() = Unit
        override fun loadOrMint(key: String, publicFormOf: (String) -> String, mint: () -> String) = mint()
        override fun storeState() = IdentityStoreState.FIRST_RUN
    }
}
