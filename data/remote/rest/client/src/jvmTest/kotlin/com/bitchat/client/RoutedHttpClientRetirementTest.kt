package com.bitchat.client

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpClientPlugin
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.util.AttributeKey
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RoutedHttpClientRetirementTest {
    @OptIn(InternalAPI::class)
    private class StuckEngine(
        testDispatcher: CoroutineDispatcher,
        override val teardownStage: String?,
    ) : HttpClientEngineBase("stuck"), RouteTeardownAwaiter {
        override val config = HttpClientEngineConfig().apply { dispatcher = testDispatcher }
        val mayRelease = CompletableDeferred<Unit>()
        var teardownCalls = 0

        override suspend fun execute(data: HttpRequestData): HttpResponseData = awaitCancellation()

        override suspend fun awaitRouteTeardown() {
            teardownCalls++
            mayRelease.await()
        }
    }

    /** Like the Curl engine: releases its sockets only in [close], and says so once however often it is closed. */
    @OptIn(InternalAPI::class)
    private class ReleasingEngine(testDispatcher: CoroutineDispatcher) :
        HttpClientEngineBase("releasing"), RouteTeardownAwaiter {
        override val config = HttpClientEngineConfig().apply { dispatcher = testDispatcher }
        private val released = CompletableDeferred<Unit>()
        var requests = 0
        var closeCalls = 0
        var releases = 0

        override suspend fun execute(data: HttpRequestData): HttpResponseData {
            requests++
            awaitCancellation()
        }

        override fun close() {
            closeCalls++
            super.close()
            if (released.complete(Unit)) releases++
        }

        override suspend fun awaitRouteTeardown() = released.await()
    }

    /** Hands Ktor an engine to manage, the way `HttpClient(engineFactory)` does in production. */
    private class ManagedBy(private val engine: ReleasingEngine) : HttpClientEngineFactory<HttpClientEngineConfig> {
        override fun create(block: HttpClientEngineConfig.() -> Unit) = engine
    }

    private suspend fun TestScope.managed(engine: ReleasingEngine) =
        RoutedHttpClient(HttpClient(ManagedBy(engine)), RouteGenerations(), RouteGenerations().admit(), Provenance(false))

    /** Makes `HttpClient.close()` itself throw: Ktor closes every installed plugin that is AutoCloseable. */
    private class ThrowsOnClose : AutoCloseable {
        override fun close() = throw IllegalStateException("close failed")
    }

    private object ThrowsOnClosePlugin : HttpClientPlugin<Unit, ThrowsOnClose> {
        override val key = AttributeKey<ThrowsOnClose>("ThrowsOnClose")
        override fun prepare(block: Unit.() -> Unit) = ThrowsOnClose()
        override fun install(plugin: ThrowsOnClose, scope: HttpClient) {}
    }

    private class Provenance(override val usedTorProxy: Boolean) : TorRouteProvenance {
        override fun isCurrent(): Boolean = true
    }

    private suspend fun TestScope.routed(
        routes: RouteGenerations,
        engine: StuckEngine,
        usedTorProxy: Boolean = false,
        configure: HttpClientConfig<*>.() -> Unit = {},
    ) = RoutedHttpClient(HttpClient(engine, configure), routes, routes.admit(), Provenance(usedTorProxy))

    private fun TestScope.engine(stage: String?) = StuckEngine(StandardTestDispatcher(testScheduler), stage)

    @Test
    fun `a stuck engine teardown names the stage the engine reports`() = runTest {
        val client = routed(RouteGenerations(), engine("releasing libcurl handles: curl_multi_cleanup"))
        val startedAt = testScheduler.currentTime

        val error = assertFailsWith<RouteRetirementException> { client.closeAndJoin() }

        assertEquals(15_000L, testScheduler.currentTime - startedAt, "the deadline is exactly 15 seconds")
        assertEquals(
            "Outbound transport did not close within 15 seconds " +
                "(still waiting for the engine to release its sockets: releasing libcurl handles: curl_multi_cleanup)",
            error.message,
        )
    }

    @Test
    fun `a stuck engine teardown without a stage names only the step`() = runTest {
        val client = routed(RouteGenerations(), engine(null))

        val error = assertFailsWith<RouteRetirementException> { client.closeAndJoin() }

        assertEquals(
            "Outbound transport did not close within 15 seconds (still waiting for the engine to release its sockets)",
            error.message,
        )
    }

    @Test
    fun `work that outlives the client cancellation names the step before the engine`() = runTest {
        val engine = engine("released")
        val client = routed(RouteGenerations(), engine)
        val release = CompletableDeferred<Unit>()
        client.client.launch { withContext(NonCancellable) { release.await() } }
        testScheduler.runCurrent()

        val error = assertFailsWith<RouteRetirementException> { client.closeAndJoin() }

        assertEquals(
            "Outbound transport did not close within 15 seconds (still ending the client's work)",
            error.message,
        )
        assertEquals(0, engine.teardownCalls, "the engine is only asked once the client's work has ended")
        release.complete(Unit)
    }

    @Test
    fun `a client whose close throws names the first step`() = runTest {
        val client = routed(RouteGenerations(), engine(null)) { install(ThrowsOnClosePlugin) }

        val error = assertFailsWith<RouteRetirementException> { client.closeAndJoin() }

        assertEquals(
            "Outbound transport did not close within 15 seconds (still closing the client)",
            error.message,
        )
        assertEquals("close failed", error.cause?.message)
    }

    @Test
    fun `a managed engine is closed when the client is retired with a request in flight`() = runTest {
        val engine = ReleasingEngine(StandardTestDispatcher(testScheduler))
        val client = managed(engine)
        client.client.launch { client.client.get("http://relay.invalid/") }
        testScheduler.runCurrent()
        assertEquals(1, engine.requests, "the request must be in flight")

        client.closeAndJoin()

        assertTrue(engine.closeCalls >= 1, "the engine was cancelled but never closed")
        assertEquals(1, engine.releases)
    }

    @Test
    fun `a managed engine closed by Ktor and again on retirement releases once`() = runTest {
        val engine = ReleasingEngine(StandardTestDispatcher(testScheduler))
        val client = managed(engine)

        client.closeAndJoin()

        assertEquals(2, engine.closeCalls, "Ktor closes it when the idle client completes and retirement closes it again")
        assertEquals(1, engine.releases)
    }

    @Test
    fun `the failed retirement is still retained and retried before ON publishes`() = runTest {
        val routes = RouteGenerations()
        val engine = engine("releasing libcurl handles")
        val client = routed(routes, engine)
        var published = false

        assertFailsWith<RouteRetirementException> { client.closeAndJoin() }
        assertEquals(1, engine.teardownCalls)

        assertFailsWith<RouteRetirementException> { routes.transition { published = true } }
        assertEquals(2, engine.teardownCalls, "the unfinished teardown was not retried")
        assertFalse(published, "ON was published while the direct engine had not released its sockets")

        engine.mayRelease.complete(Unit)
        routes.transition { published = true }

        assertTrue(published)
    }

    @Test
    fun `a failed Tor routed retirement is not retained`() = runTest {
        val routes = RouteGenerations()
        val engine = engine("releasing libcurl handles")
        val client = routed(routes, engine, usedTorProxy = true)
        var published = false

        assertFailsWith<RouteRetirementException> { client.closeAndJoin() }
        routes.transition { published = true }

        assertTrue(published)
        assertEquals(1, engine.teardownCalls)
    }
}
