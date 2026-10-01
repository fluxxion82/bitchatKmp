@file:OptIn(ExperimentalAtomicApi::class)

package com.bitchat.client

import com.bitchat.client.harness.LocalScriptedServer
import com.bitchat.client.harness.PosixNet
import com.bitchat.client.harness.ReadyTorRouteSource
import com.bitchat.client.harness.ServerScript
import com.bitchat.domain.initialization.models.AppInformation
import com.bitchat.domain.initialization.models.Version
import com.bitchat.domain.tor.RequestedTorIntent
import com.bitchat.domain.tor.model.TorMode
import com.bitchat.domain.tor.model.TorStatus
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** The route-transition safety bar: direct sockets are gone at their servers before ON publishes. */
class DarwinRouteTransitionTest {
    @Test
    fun offToOnClosesEveryDirectSocketBeforePolicyPublication() = runBlocking {
        PosixNet.ignoreSigpipe()
        var transitioned = false
        repeat(MAX_SETUP_ATTEMPTS) { attempt ->
            if (runTransitionAttempt(attempt + 1)) {
                transitioned = true
                return@runBlocking
            }
        }
        assertTrue(transitioned, "could not obtain an open idle keep-alive connection in $MAX_SETUP_ATTEMPTS setup attempts")
    }

    private suspend fun runTransitionAttempt(attempt: Int): Boolean {
        val intent = MutableIntent(TorMode.OFF)
        val provider = RouteAwareClientProvider(
            appInformation = AppInformation(Version("test", "test", ""), 1, "test", false),
            requestedIntent = intent,
            torRouteSource = ReadyTorRouteSource(9150),
            engineSupportsTorProxy = true,
        )
        val established = LocalScriptedServer(ServerScript.WebSocketUpgrade)
        val pending = LocalScriptedServer(ServerScript.HoldAfterRequest)
        val active = LocalScriptedServer(ServerScript.HoldAfterRequest)
        val idle = LocalScriptedServer(ServerScript.KeepAliveOk)
        val ready = CompletableDeferred<Unit>()
        val starts = AtomicReference<List<String>>(emptyList())

        return try {
            coroutineScope {
                val establishedJob = async {
                    provider.useWebSocketRoute { client, _ ->
                        client.webSocketSession("ws://127.0.0.1:${established.port}/")
                        check(established.awaitResponded(0, 5.seconds)) { "established socket did not upgrade" }
                        record(starts, "established")
                        if (starts.load().size == 4) ready.complete(Unit)
                        awaitCancellation()
                    }
                }
                val pendingJob = async {
                    provider.useWebSocketRoute { client, _ ->
                        record(starts, "pending-started")
                        client.webSocketSession("ws://127.0.0.1:${pending.port}/")
                    }
                }
                val activeJob = async {
                    provider.withRestClient(com.bitchat.client.model.ClientType.NOSTR, emptyList()) { client ->
                        record(starts, "active-started")
                        client.get("http://127.0.0.1:${active.port}/")
                    }
                }
                val idleJob = async {
                    provider.withRestClient(com.bitchat.client.model.ClientType.NOSTR, emptyList()) { client ->
                        check(client.get("http://127.0.0.1:${idle.port}/").bodyAsText() == "ok")
                        record(starts, "idle")
                        if (starts.load().size == 4) ready.complete(Unit)
                        awaitCancellation()
                    }
                }

                try {
                    check(pending.awaitRequests(1, 5.seconds)) { "pending handshake never reached server" }
                    check(active.awaitRequests(1, 5.seconds)) { "active REST never reached server" }
                    withTimeout(5.seconds) { ready.await() }

                    if (!isOpen(idle)) {
                        println("attempt=$attempt idle keep-alive was not open/pooled; retrying setup: ${idle.describe()}")
                        return@coroutineScope false
                    }
                    listOf("established", "pending", "active", "idle").zip(listOf(established, pending, active, idle)).forEach { (name, server) ->
                        assertTrue(isOpen(server), "$name was not open immediately before the transition: ${server.describe()}")
                    }
                    provider.transition(waitForDirectRetirements = true) {
                        val publicationMark = TimeSource.Monotonic.markNow()
                        intent.current = TorMode.ON
                        listOf("established", "pending", "active", "idle").zip(listOf(established, pending, active, idle)).forEach { (name, server) ->
                            val close = server.connections().singleOrNull()?.closeObservedAt?.load()
                            assertNotNull(close, "$name remained open when ON was published: ${server.describe()}")
                            val margin = publicationMark - close
                            assertTrue(margin >= Duration.ZERO, "$name closed after ON was published by ${-margin}: ${server.describe()}")
                            println("ORDER $name closeBeforePublish=$margin")
                        }
                    }

                    true
                } finally {
                    listOf(establishedJob, pendingJob, activeJob, idleJob).forEach { it.cancelAndJoin() }
                }
            }
        } finally {
            established.stop(); pending.stop(); active.stop(); idle.stop()
        }
    }

    @Test
    fun directRequestsStillWorkAfterOnOffRouteCycles() = runBlocking {
        PosixNet.ignoreSigpipe()
        val intent = MutableIntent(TorMode.ON)
        val provider = RouteAwareClientProvider(
            appInformation = AppInformation(Version("test", "test", ""), 1, "test", false),
            requestedIntent = intent,
            torRouteSource = ReadyTorRouteSource(9150),
            engineSupportsTorProxy = true,
        )
        val server = LocalScriptedServer(ServerScript.KeepAliveOk)
        try {
            provider.transition(waitForDirectRetirements = false) { intent.current = TorMode.OFF }
            assertTrue(directGet(provider, server.port) == "ok")
            provider.transition { intent.current = TorMode.ON }
            provider.transition(waitForDirectRetirements = false) { intent.current = TorMode.OFF }
            assertTrue(directGet(provider, server.port) == "ok")
        } finally {
            server.stop()
        }
    }

    private suspend fun directGet(provider: RouteAwareClientProvider, port: Int): String =
        provider.withRestClient(com.bitchat.client.model.ClientType.NOSTR, emptyList()) { client ->
            client.get("http://127.0.0.1:$port/").bodyAsText()
        }

    private fun record(target: AtomicReference<List<String>>, value: String) {
        while (true) {
            val current = target.load()
            if (target.compareAndSet(current, current + value)) return
        }
    }

    private fun isOpen(server: LocalScriptedServer): Boolean =
        server.connections().singleOrNull()?.closeObservedAt?.load() == null

    private class MutableIntent(initial: TorMode) : RequestedTorIntent {
        private val state = MutableStateFlow(initial)
        override var current: TorMode
            get() = state.value
            set(value) { state.value = value }
        override val updates: StateFlow<TorMode> = state
    }

    private companion object {
        const val MAX_SETUP_ATTEMPTS = 3
    }
}
