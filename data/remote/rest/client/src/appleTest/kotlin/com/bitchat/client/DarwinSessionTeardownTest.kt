@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package com.bitchat.client

import com.bitchat.client.harness.LocalScriptedServer
import com.bitchat.client.harness.OwnedSessionRegistry
import com.bitchat.client.harness.PosixNet
import com.bitchat.client.harness.ServerScript
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSURLSession
import platform.posix.getenv
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A2 teardown spike (B1.3, B2.1, B2.2): can we own and AWAIT Darwin teardown? Direct route
 * (intent OFF, the leak case: direct sockets surviving into ON), local servers that stamp the
 * moment the client's socket goes away. Per case, N repetitions of: establish the situation,
 * `invalidateAndCancel()` on the owned session, await `didBecomeInvalidWithError:`, record when
 * the SERVER observed the close relative to that acknowledgement, and check Ktor's continuations
 * finish. GO for Task 3 only if every case closes at the server before the acknowledgement or
 * within [CLOSE_BOUND] after it, every time.
 *
 * `BITCHAT_TEARDOWN_REPS` overrides the repetition count.
 */
class DarwinSessionTeardownTest {

    private enum class Scenario { EstablishedWebSocket, PendingHandshake, ActiveRest, IdleKeepAlive }

    private enum class PreInvalidate { None, Flush, Reset }

    @Test
    fun establishedWebSocketClosesAtServerOnInvalidate() =
        measure("established-ws", ServerScript.WebSocketUpgrade, Scenario.EstablishedWebSocket)

    @Test
    fun pendingHandshakeClosesAtServerOnInvalidate() =
        measure("pending-handshake", ServerScript.HoldAfterRequest, Scenario.PendingHandshake)

    @Test
    fun activeRestCallClosesAtServerOnInvalidate() =
        measure("active-rest", ServerScript.HoldAfterRequest, Scenario.ActiveRest)

    @Test
    fun idleKeepAliveClosesAtServerOnInvalidate() =
        measure("idle-keepalive", ServerScript.KeepAliveOk, Scenario.IdleKeepAlive)

    /** Informational: does `flushWithCompletionHandler` alone retire the idle keep-alive socket? */
    @Test
    fun idleKeepAliveAfterFlushClosesAtServer() =
        measure("idle-keepalive-flush", ServerScript.KeepAliveOk, Scenario.IdleKeepAlive, PreInvalidate.Flush)

    /** Informational: does `resetWithCompletionHandler` alone retire the idle keep-alive socket? */
    @Test
    fun idleKeepAliveAfterResetClosesAtServer() =
        measure("idle-keepalive-reset", ServerScript.KeepAliveOk, Scenario.IdleKeepAlive, PreInvalidate.Reset)

    // ---- measurement ---------------------------------------------------------------------------

    private class Sample(
        val rep: Int,
        val invalidation: DarwinInvalidation,
        /** Server-observed close minus the invalidation acknowledgement; negative = closed first. */
        val closeDelta: Duration?,
        val closeAfterInvalidate: Duration?,
        val closeKind: String?,
        val bytesAfter: Int,
        val ktorDone: Boolean,
        val connections: Int,
        /** Idle keep-alive only: was the socket still open when we started tearing down? */
        val pooled: Boolean?,
        /** Pre-invalidate variants only: did flush/reset alone close the socket? */
        val closedBeforeInvalidate: Boolean,
        val preDelay: Duration?,
    ) {
        val closed: Boolean get() = closeDelta != null || closedBeforeInvalidate

        override fun toString(): String = buildString {
            append("rep=$rep ack=${invalidation.ackDelay?.ms() ?: "NONE"} ")
            append("close=${closeDelta?.ms() ?: "NOT OBSERVED"} afterInvalidate=${closeAfterInvalidate?.ms() ?: "-"} kind=$closeKind ")
            append("bytesAfter=$bytesAfter conns=$connections ktorDone=$ktorDone")
            pooled?.let { append(" pooled=$it") }
            preDelay?.let { append(" pre=${it.ms()} closedBeforeInvalidate=$closedBeforeInvalidate") }
        }
    }

    private fun measure(case: String, script: ServerScript, scenario: Scenario, pre: PreInvalidate = PreInvalidate.None) = runBlocking {
        PosixNet.ignoreSigpipe()
        val requested = getenv(REPS_VARIABLE)?.toKString()?.toIntOrNull() ?: DEFAULT_REPS
        // The flush/reset variants are informational and each rep waits PRE_CLOSE_WAIT for a close
        // that never comes; cap them so the class stays well inside the gate's time budget.
        val reps = if (pre == PreInvalidate.None) requested else minOf(requested, INFORMATIONAL_REPS)
        println("[$case] start reps=$reps scenario=$scenario pre=$pre")
        val samples = mutableListOf<Sample>()
        var unclosedInARow = 0
        var attempt = 0
        while (samples.size < reps && attempt < reps * MAX_IDLE_ATTEMPTS) {
            attempt++
            val sample = withTimeout(REP_BOUND) { runRep(attempt, script, scenario, pre) }
            if (scenario == Scenario.IdleKeepAlive && sample.pooled != true) {
                println("[$case] attempt=$attempt did not leave a connection idle in the pool; retrying")
                continue
            }
            samples += sample
            println("[$case] $sample")
            if (sample.closed) {
                unclosedInARow = 0
            } else if (++unclosedInARow >= EARLY_STOP) {
                println("[$case] stopping after attempt $attempt: $EARLY_STOP consecutive reps without a server-observed close")
                break
            }
        }

        val deltas = samples.mapNotNull { it.closeDelta }
        val afterInvalidate = samples.mapNotNull { it.closeAfterInvalidate }
        val summary = buildString {
            append("TEARDOWN $case reps=${samples.size} acknowledged=${samples.count { it.invalidation.acknowledged }} ")
            append("closedAtServer=${samples.count { it.closed }} ")
            append("closeMinusAck[min=${deltas.minOrNull()?.ms() ?: "-"} avg=${deltas.averageMs()} max=${deltas.maxOrNull()?.ms() ?: "-"}] ")
            append("closeAfterInvalidate[max=${afterInvalidate.maxOrNull()?.ms() ?: "-"}] ")
            append("ackDelay[max=${samples.mapNotNull { it.invalidation.ackDelay }.maxOrNull()?.ms() ?: "-"}] ")
            append("ktorDone=${samples.count { it.ktorDone }} kinds=${samples.groupingBy { it.closeKind ?: "none" }.eachCount()} ")
            append("bytesAfter[max=${samples.maxOf { it.bytesAfter }}] conns[max=${samples.maxOf { it.connections }}]")
            if (scenario == Scenario.IdleKeepAlive) append(" pooled=${samples.count { it.pooled == true }}/${samples.size}")
            if (pre != PreInvalidate.None) {
                append(" closedByPreStepAlone=${samples.count { it.closedBeforeInvalidate }}/${samples.size}")
                append(" preDelay[max=${samples.mapNotNull { it.preDelay }.maxOrNull()?.ms() ?: "-"}]")
            }
        }
        println(summary)

        assertTrue(samples.all { it.invalidation.acknowledged }, "NO-GO: didBecomeInvalidWithError was not delivered in every rep; $summary")
        assertEquals(
            samples.size,
            samples.count { it.closed },
            "NO-GO: the server did not observe a close within $CLOSE_WAIT of invalidateAndCancel in every rep; $summary",
        )
        assertTrue(samples.all { it.ktorDone }, "NO-GO: a Ktor continuation was stranded after invalidation; $summary")
        val worst = deltas.maxOrNull()
        assertTrue(
            worst == null || worst <= CLOSE_BOUND,
            "NO-GO: server-observed close lagged the acknowledgement by ${worst?.ms()}, bound is $CLOSE_BOUND; $summary",
        )
        if (scenario == Scenario.IdleKeepAlive) {
            assertEquals(reps, samples.size, "could not obtain $reps idle-pool reps in ${reps * MAX_IDLE_ATTEMPTS} attempts; $summary")
            assertTrue(samples.all { it.pooled == true }, "the keep-alive socket was not idle in the pool when teardown started; $summary")
        }
    }

    private suspend fun runRep(rep: Int, script: ServerScript, scenario: Scenario, pre: PreInvalidate): Sample = coroutineScope {
        val server = LocalScriptedServer(script)
        val sessions = OwnedSessionRegistry()
        val client = HttpClient(sessions.factory()) { install(WebSockets) }
        val httpUrl = "http://127.0.0.1:${server.port}/"
        val wsUrl = "ws://127.0.0.1:${server.port}/"
        var ktorWork: Deferred<Result<Unit>>? = null
        var pooled: Boolean? = null
        var closedBeforeInvalidate = false
        var preDelay: Duration? = null
        try {
            when (scenario) {
                Scenario.EstablishedWebSocket -> {
                    val webSocket = client.webSocketSession(wsUrl)
                    check(server.awaitResponded(0, 5.seconds)) { "server never completed the upgrade: ${server.describe()}" }
                    ktorWork = async { runCatching { for (frame in webSocket.incoming) { /* drain until the engine ends it */ } } }
                }
                Scenario.PendingHandshake -> {
                    ktorWork = async { runCatching { client.webSocketSession(wsUrl); Unit } }
                    check(server.awaitRequests(1, 5.seconds)) { "server never saw the handshake: ${server.describe()}" }
                }
                Scenario.ActiveRest -> {
                    ktorWork = async { runCatching { client.get(httpUrl); Unit } }
                    check(server.awaitRequests(1, 5.seconds)) { "server never saw the request: ${server.describe()}" }
                }
                Scenario.IdleKeepAlive -> {
                    val body = client.get(httpUrl).bodyAsText()
                    check(body == "ok") { "unexpected body $body" }
                    delay(POOL_SETTLE)
                    pooled = server.connections().firstOrNull()?.closeObservedAt?.load() == null
                    if (pre != PreInvalidate.None) {
                        val session = sessions.single().session
                        val started = TimeSource.Monotonic.markNow()
                        val completed = when (pre) {
                            PreInvalidate.Flush -> session.flushAndAwait()
                            PreInvalidate.Reset -> session.resetAndAwait()
                            PreInvalidate.None -> true
                        }
                        preDelay = started.elapsedNow()
                        check(completed) { "$pre completion handler never ran" }
                        closedBeforeInvalidate = server.awaitClose(0, PRE_CLOSE_WAIT)
                    }
                }
            }

            val owned = sessions.single()
            val invalidation = owned.invalidateAndAwait(10.seconds)
            server.awaitClose(0, CLOSE_WAIT)
            val record = server.connections().firstOrNull()
            val closeAt = record?.closeObservedAt?.load()
            val acknowledgedAt = invalidation.acknowledgedAt
            val ktorDone = ktorWork?.let { withTimeoutOrNull(KTOR_BOUND) { it.await() } != null } ?: true
            Sample(
                rep = rep,
                invalidation = invalidation,
                closeDelta = if (closeAt != null && acknowledgedAt != null) closeAt - acknowledgedAt else null,
                closeAfterInvalidate = closeAt?.minus(invalidation.startedAt),
                closeKind = record?.closeKind?.load(),
                bytesAfter = record?.bytesAfterResponse?.load() ?: 0,
                ktorDone = ktorDone,
                connections = server.connections().size,
                pooled = pooled,
                closedBeforeInvalidate = closedBeforeInvalidate,
                preDelay = preDelay,
            )
        } finally {
            ktorWork?.cancel()
            client.close()
            withTimeoutOrNull(5.seconds) { client.coroutineContext[Job]?.join() }
            server.stop()
        }
    }

    private suspend fun NSURLSession.flushAndAwait(): Boolean = withTimeoutOrNull(5.seconds) {
        suspendCancellableCoroutine<Unit> { continuation -> flushWithCompletionHandler { continuation.resume(Unit) } }
    } != null

    private suspend fun NSURLSession.resetAndAwait(): Boolean = withTimeoutOrNull(5.seconds) {
        suspendCancellableCoroutine<Unit> { continuation -> resetWithCompletionHandler { continuation.resume(Unit) } }
    } != null

    private companion object {
        const val REPS_VARIABLE = "BITCHAT_TEARDOWN_REPS"
        const val DEFAULT_REPS = 20
        const val INFORMATIONAL_REPS = 5
        const val EARLY_STOP = 3
        const val MAX_IDLE_ATTEMPTS = 3
        val REP_BOUND = 30.seconds
        /** How long a rep waits for the server to see the close after invalidation. */
        val CLOSE_WAIT = 3.seconds
        /** The GO bar is the production route-publication settle bound. */
        val CLOSE_BOUND = DARWIN_ROUTE_TEARDOWN_SETTLE_BOUND
        val KTOR_BOUND = 5.seconds
        val PRE_CLOSE_WAIT = 300.milliseconds
        val POOL_SETTLE = 200.milliseconds
    }
}

private fun Duration.ms(): String = "${(inWholeMicroseconds / 100) / 10.0}ms"

private fun List<Duration>.averageMs(): String =
    if (isEmpty()) "-" else "${((sumOf { it.inWholeMicroseconds } / size) / 100) / 10.0}ms"
