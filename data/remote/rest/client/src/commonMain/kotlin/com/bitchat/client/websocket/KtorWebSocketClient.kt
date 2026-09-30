package com.bitchat.client.websocket

import com.bitchat.client.WebSocketRouteProvider
import com.bitchat.domain.base.logBody
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.pow
import kotlin.time.Duration.Companion.seconds

internal class KtorWebSocketClient(
    private val routeProvider: WebSocketRouteProvider,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    internal val activeConnections = mutableMapOf<String, WebSocketConnection>()

    internal data class ReconnectPolicy(
        val listener: WebSocketListener,
        val maxReconnectAttempts: Int,
        val initialBackoffMs: Long,
        val maxBackoffMs: Long,
        val backoffMultiplier: Double,
    )

    data class WebSocketConnection(
        val url: String,
        var reconnectAttempts: Int = 0,
        var job: Job? = null,
        var reconnectJob: Job? = null,
        var session: WebSocketSession? = null,
        var route: com.bitchat.client.TorRouteProvenance? = null,
        internal var reconnectPolicy: ReconnectPolicy? = null,
        /** Identity of the reader allowed to report on this connection; replaced per launch. */
        internal var owner: Any? = null,
    )

    /**
     * [resetBudget] distinguishes a caller asking for a connection from the internal retry loop
     * asking again.
     *
     * The budget resets only on success, so once ten attempts were spent the connection stayed
     * dead for the life of the process: a later call would try once and then decline to reschedule.
     * That is survivable when failures are transient, and not survivable when the cause was a
     * policy the user has since changed -- switching Tor off produced no event that could revive
     * the relay. An explicit call is a fresh start; the retry loop passes false so its ceiling
     * still means something.
     */
    fun connect(
        url: String,
        listener: WebSocketListener,
        maxReconnectAttempts: Int = 10,
        initialBackoffMs: Long = 1000L,
        maxBackoffMs: Long = 60000L,
        backoffMultiplier: Double = 2.0,
        resetBudget: Boolean = true,
    ) {
        println("KtorWebSocketClient: connect() marker v2025-12-31b for $url")
        val connection = activeConnections.getOrPut(url) {
            WebSocketConnection(url)
        }
        if (resetBudget && connection.reconnectAttempts > 0) {
            println("KtorWebSocketClient: resetting retry budget for $url")
            connection.reconnectAttempts = 0
        }
        connection.reconnectPolicy = ReconnectPolicy(
            listener = listener,
            maxReconnectAttempts = maxReconnectAttempts,
            initialBackoffMs = initialBackoffMs,
            maxBackoffMs = maxBackoffMs,
            backoffMultiplier = backoffMultiplier,
        )

        // A live socket is reusable only while its route provenance remains current.
        if (connection.session?.isActive == true) {
            if (connection.route?.isCurrent() == true) {
                println("⚠️ KtorWebSocketClient: Already connected to $url, skipping duplicate connect()")
                return
            }
            println("KtorWebSocketClient: retiring stale route for $url before reconnecting")
            retireConnection(connection)
        }

        // ADDED: Early return if connection already in progress
        if (connection.job?.isActive == true && connection.session == null) {
            println("⚠️ KtorWebSocketClient: Connection to $url already in progress, skipping duplicate connect()")
            return
        }

        // Only cancel jobs if we're definitely reconnecting (failed or not started)
        connection.job?.cancel()
        connection.reconnectJob?.cancel()

        val owner = Any()
        connection.owner = owner
        connection.job = scope.launch {
            var session: WebSocketSession? = null

            // True once a newer launch or a retirement took this connection over. An obsolete
            // reader must not report closure or failure: Nostr would mark the replacement's relay
            // disconnected. Ownership is set before launch, so a stored session left behind by an
            // unwinding older reader cannot make the current reader look obsolete.
            fun superseded(): Boolean = connection.owner !== owner

            try {
                println("KtorWebSocketClient: 🔌 Attempting connection to $url")
                routeProvider.useWebSocketRoute { client, route ->
                    session = withTimeoutOrNull(15.seconds) {
                        client.webSocketSession(url)
                    }
                    if (session == null) {
                        println("KtorWebSocketClient: ⏳ WebSocket connect timeout for $url")
                        if (!superseded()) {
                            listener.onFailure(url, IllegalStateException("WebSocket connection timeout"))
                        }
                        return@useWebSocketRoute
                    }

                    val opened = session!!
                    check(route.isCurrent()) { "WebSocket route was retired before session publication" }
                    println("KtorWebSocketClient: ✅ WebSocket session established for $url")
                    connection.reconnectAttempts = 0
                    connection.session = opened
                    connection.route = route
                    listener.onOpen(url, route)

                    for (frame in opened.incoming) {
                        if (!scope.isActive) break

                        when (frame) {
                            is Frame.Text -> {
                                try {
                                    val messageText = frame.readText()
                                    listener.onMessage(url, messageText)
                                    println("✅ KtorWebSocketClient: Message handler completed for $url, Message: ${logBody(messageText)}")
                                } catch (e: Exception) {
                                    listener.onFailure(url, e)
                                }
                            }

                            is Frame.Close -> {
                                val closeReason = frame.readReason()
                                val code = closeReason?.code?.toInt() ?: 1000
                                val reason = closeReason?.message ?: "Unknown"
                                if (!superseded()) {
                                    listener.onClosing(url, code, reason)
                                    listener.onClosed(url, code, reason)
                                }
                                if (connection.session === opened) {
                                    connection.session = null
                                    connection.route = null
                                }
                                return@useWebSocketRoute
                            }

                            else -> Unit
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("KtorWebSocketClient: ❌ Connection failed for $url: ${e.message}")
                println("KtorWebSocketClient: Exception type: ${e::class.simpleName}")
                e.printStackTrace()
                // This reader may have been retired while a replacement was connecting. Never
                // erase that replacement merely because the old route's teardown later failed.
                if (superseded()) {
                    println("KtorWebSocketClient: ignoring failure from a superseded reader for $url")
                    return@launch
                }
                if (connection.session === session) {
                    connection.session = null
                    connection.route = null
                }
                listener.onFailure(url, e)
            } finally {
                if (connection.session === session) {
                    connection.session = null
                    connection.route = null
                }
                // A cancelled reader does not cancel Ktor's client-owned websocket job. Always
                // close the owned session before returning the route lease, including cancellation.
                session?.let { opened ->
                    runCatching { opened.close(CloseReason(1000, "Session closed")) }
                }
            }

            // Schedule reconnection if we haven't exceeded max attempts
            if (connection.reconnectAttempts < maxReconnectAttempts && scope.isActive) {
                println("KtorWebSocketClient: 🔄 Scheduling reconnection (attempt ${connection.reconnectAttempts + 1}/$maxReconnectAttempts)")
                scheduleReconnection(
                    url = url,
                    listener = listener,
                    connection = connection,
                    maxReconnectAttempts = maxReconnectAttempts,
                    initialBackoffMs = initialBackoffMs,
                    maxBackoffMs = maxBackoffMs,
                    backoffMultiplier = backoffMultiplier
                )
            }
        }
    }

    private fun scheduleReconnection(
        url: String,
        listener: WebSocketListener,
        connection: WebSocketConnection,
        maxReconnectAttempts: Int,
        initialBackoffMs: Long,
        maxBackoffMs: Long,
        backoffMultiplier: Double
    ) {
        if (connection.reconnectJob?.isActive == true) return
        connection.reconnectAttempts++

        // Calculate backoff delay with exponential growth
        val backoffDelay = (initialBackoffMs * backoffMultiplier.pow(connection.reconnectAttempts - 1.0))
            .toLong()
            .coerceAtMost(maxBackoffMs)

        connection.reconnectJob = scope.launch {
            delay(backoffDelay)
            if (scope.isActive) {
                connect(
                    url = url,
                    listener = listener,
                    maxReconnectAttempts = maxReconnectAttempts,
                    initialBackoffMs = initialBackoffMs,
                    maxBackoffMs = maxBackoffMs,
                    backoffMultiplier = backoffMultiplier,
                    // The retry loop must not refill its own budget.
                    resetBudget = false,
                )
            }
        }
    }

    /**
     * Send a text message over the WebSocket connection.
     * Requires an active connection session.
     */
    suspend fun send(url: String, message: String) {
        try {
//            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
//            println("📤 KtorWebSocketClient.send STARTED")
//            println("   Relay URL: $url")
//            println("   Message length: ${message.length} chars")
//            println("   Message preview: ${message.take(100)}${if (message.length > 100) "..." else ""}")
//            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

            val connection = activeConnections[url]
            if (connection == null) {
                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                println("❌ KtorWebSocketClient.send FAILED")
                println("   Reason: No active connection found for $url")
                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                return
            }

            val session = connection.session
            if (session == null) {
                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                println("❌ KtorWebSocketClient.send FAILED")
                println("   Reason: No active session for $url")
                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                return
            }

            if (connection.route?.isCurrent() != true) {
                println("KtorWebSocketClient.send refused: route for $url was retired")
                try {
                    session.close(CloseReason(1000, "Route retired"))
                } catch (_: Exception) {
                    // The cancelled reader also closes its owned session in its finally block.
                }
                retireConnection(connection)
                return
            }

            session.send(Frame.Text(message))

//            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
//            println("✅ KtorWebSocketClient.send COMPLETED")
//            println("   Successfully sent to $url")
//            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        } catch (e: Exception) {
            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            println("❌ KtorWebSocketClient.send EXCEPTION")
            println("   Relay URL: $url")
            println("   Error: ${e.message}")
            println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            e.printStackTrace()
            activeConnections[url]?.let(::retireConnection)
        }
    }

    /** Cancels exactly the reader that owns this stale session, never another relay's reader. */
    private fun retireConnection(connection: WebSocketConnection) {
        connection.owner = null
        connection.session = null
        connection.route = null
        connection.reconnectJob?.cancel()
        connection.reconnectJob = null
        val reader = connection.job
        connection.job = null
        reader?.cancel()
        connection.reconnectPolicy?.let { policy ->
            scheduleReconnection(
                url = connection.url,
                listener = policy.listener,
                connection = connection,
                maxReconnectAttempts = policy.maxReconnectAttempts,
                initialBackoffMs = policy.initialBackoffMs,
                maxBackoffMs = policy.maxBackoffMs,
                backoffMultiplier = policy.backoffMultiplier,
            )
        }
    }

    /**
     * Disconnect from a WebSocket URL.
     */
    suspend fun disconnect(url: String) {
        val connection = activeConnections.remove(url) ?: return
        connection.owner = null
        connection.reconnectJob?.cancelAndJoin()

        try {
            /*
             * Close the session we hold. This used to dial a brand new WebSocket purely so it
             * could close it -- which achieved nothing, and under Tor enforcement is itself an
             * outbound connection attempt made while disconnecting.
             */
            connection.session?.close(CloseReason(CloseReason.Codes.NORMAL, "Normal closure"))
        } catch (e: Exception) {
            // Already closed, or the session never opened.
        } finally {
            connection.session = null
            connection.route = null
            connection.job?.cancelAndJoin()
        }
    }

    /**
     * Check if connected to a WebSocket URL.
     */
    fun isConnected(url: String): Boolean {
        val connection = activeConnections[url] ?: return false
        return connection.session?.isActive == true && connection.route?.isCurrent() == true
    }

    /**
     * Check if a connection is currently in progress for a WebSocket URL.
     * Returns true if a connection job is active but the session is not yet established.
     */
    fun isConnecting(url: String): Boolean {
        val connection = activeConnections[url] ?: return false
        // Connection is "connecting" if job is active but session is not yet established
        return connection.job?.isActive == true && connection.session == null
    }

    /**
     * Close all active connections and cancel the scope.
     */
    fun shutdown() {
        scope.launch {
            activeConnections.values.forEach { connection ->
                connection.job?.cancel()
                connection.reconnectJob?.cancel()
            }
            activeConnections.clear()
        }
    }
}
