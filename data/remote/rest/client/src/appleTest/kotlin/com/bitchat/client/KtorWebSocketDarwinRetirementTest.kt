@file:OptIn(ExperimentalAtomicApi::class)

package com.bitchat.client

import com.bitchat.client.harness.LocalScriptedServer
import com.bitchat.client.harness.OwnedSessionRegistry
import com.bitchat.client.harness.PosixNet
import com.bitchat.client.harness.ReadyTorRouteSource
import com.bitchat.client.harness.ServerScript
import com.bitchat.client.websocket.KtorWebSocketClient
import com.bitchat.client.websocket.WebSocketListener
import com.bitchat.domain.initialization.models.AppInformation
import com.bitchat.domain.initialization.models.Version
import com.bitchat.domain.tor.RequestedTorIntent
import com.bitchat.domain.tor.model.TorMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Exercises the actor through Darwin's owned NSURLSession engine, not a hand-built session. */
class KtorWebSocketDarwinRetirementTest {
    @Test
    fun controllerDisconnectAndShutdownInvalidateAndSettleDarwinSessions() = runBlocking {
        PosixNet.ignoreSigpipe()
        val intent = MutableIntent(TorMode.OFF)
        val sessions = OwnedSessionRegistry()
        val provider = provider(intent, sessions)
        val client = KtorWebSocketClient(provider)
        val first = LocalScriptedServer(ServerScript.WebSocketUpgrade)
        val second = LocalScriptedServer(ServerScript.WebSocketUpgrade)
        try {
            connect(client, first)
            client.disconnect(url(first))
            assertTrue(first.awaitClose(0, 5.seconds), "disconnect did not close direct socket: ${first.describe()}")
            assertTrue(sessions.all().single().invalidateAndAwait().acknowledged, "disconnect did not settle NSURLSession invalidation")

            connect(client, second)
            client.shutdown()
            assertTrue(second.awaitClose(0, 5.seconds), "shutdown did not close direct socket: ${second.describe()}")
            assertTrue(sessions.all().last().invalidateAndAwait().acknowledged, "shutdown did not settle NSURLSession invalidation")
        } finally {
            client.shutdown()
            first.stop()
            second.stop()
        }
    }

    @Test
    fun offToOnClosesActorOwnedDirectSocketBeforePublication() = runBlocking {
        PosixNet.ignoreSigpipe()
        val intent = MutableIntent(TorMode.OFF)
        val sessions = OwnedSessionRegistry()
        val provider = provider(intent, sessions)
        val client = KtorWebSocketClient(provider)
        val server = LocalScriptedServer(ServerScript.WebSocketUpgrade)
        try {
            connect(client, server)
            provider.transition(waitForDirectRetirements = true) {
                val closed = server.connections().singleOrNull()?.closeObservedAt?.load()
                assertNotNull(closed, "actor-owned direct socket remained open when ON was published: ${server.describe()}")
                intent.current = TorMode.ON
            }
            assertTrue(sessions.all().single().invalidateAndAwait().acknowledged, "route transition did not settle NSURLSession invalidation")
        } finally {
            client.shutdown()
            server.stop()
        }
    }

    private suspend fun connect(client: KtorWebSocketClient, server: LocalScriptedServer) {
        val opened = CompletableDeferred<Unit>()
        client.connect(url(server), listener(opened), maxReconnectAttempts = 0)
        withTimeout(5.seconds) { opened.await() }
        assertTrue(server.awaitResponded(0, 5.seconds), "WebSocket did not upgrade: ${server.describe()}")
    }

    private fun listener(opened: CompletableDeferred<Unit>) = object : WebSocketListener {
        override fun onOpen(url: String, route: TorRouteProvenance) { opened.complete(Unit) }
        override fun onMessage(url: String, text: String) = Unit
        override fun onClosing(url: String, code: Int, reason: String) = Unit
        override fun onClosed(url: String, code: Int, reason: String) = Unit
        override fun onFailure(url: String, t: Throwable) = Unit
    }

    private fun provider(intent: RequestedTorIntent, sessions: OwnedSessionRegistry) = RouteAwareClientProvider(
        appInformation = AppInformation(Version("test", "test", ""), 1, "test", false),
        requestedIntent = intent,
        torRouteSource = ReadyTorRouteSource(9150),
        engineSupportsTorProxy = true,
        engineFactory = sessions.engineFactory(),
    )

    private fun url(server: LocalScriptedServer) = "ws://127.0.0.1:${server.port}/"

    private class MutableIntent(initial: TorMode) : RequestedTorIntent {
        private val state = MutableStateFlow(initial)
        override var current: TorMode
            get() = state.value
            set(value) { state.value = value }
        override val updates: StateFlow<TorMode> = state
    }
}
