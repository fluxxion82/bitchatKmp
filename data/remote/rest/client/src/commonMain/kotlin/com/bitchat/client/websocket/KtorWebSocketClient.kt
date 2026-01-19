package com.bitchat.client.websocket

import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.pow
import kotlin.time.Duration.Companion.seconds

internal class KtorWebSocketClient(
    private val httpClient: HttpClient,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val activeConnections = mutableMapOf<String, WebSocketConnection>()

    data class WebSocketConnection(
        val url: String,
        var reconnectAttempts: Int = 0,
        var job: Job? = null,
        var reconnectJob: Job? = null,
        var session: WebSocketSession? = null
    )

    fun connect(
        url: String,
        listener: WebSocketListener,
        maxReconnectAttempts: Int = 10,
        initialBackoffMs: Long = 1000L,
        maxBackoffMs: Long = 60000L,
        backoffMultiplier: Double = 2.0
    ) {
        println("KtorWebSocketClient: connect() marker v2025-12-31b for $url")
        val connection = activeConnections.getOrPut(url) {
            WebSocketConnection(url)
        }

        // ADDED: Early return if already successfully connected
        if (connection.session?.isActive == true) {
            println("⚠️ KtorWebSocketClient: Already connected to $url, skipping duplicate connect()")
            return
        }

        // ADDED: Early return if connection already in progress
        if (connection.job?.isActive == true && connection.session == null) {
            println("⚠️ KtorWebSocketClient: Connection to $url already in progress, skipping duplicate connect()")
            return
        }

        // Only cancel jobs if we're definitely reconnecting (failed or not started)
        connection.job?.cancel()
        connection.reconnectJob?.cancel()

        connection.job = scope.launch {
            var session: WebSocketSession? = null
            try {
                println("KtorWebSocketClient: 🔌 Attempting connection to $url")
                session = withTimeoutOrNull(15.seconds) {
                    httpClient.webSocketSession(url)
                }
                if (session == null) {
                    println("KtorWebSocketClient: ⏳ WebSocket connect timeout for $url")
                    listener.onFailure(url, IllegalStateException("WebSocket connection timeout"))
                    return@launch
                }

                println("KtorWebSocketClient: ✅ WebSocket session established for $url")
                connection.reconnectAttempts = 0
                connection.session = session
                listener.onOpen(url)

                // Read messages until connection closes
                for (frame in session.incoming) {
                    if (!scope.isActive) break

                    when (frame) {
                        is Frame.Text -> {
                            try {
                                val messageText = frame.readText()
//                                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
//                                println("📥 KtorWebSocketClient RECEIVED MESSAGE")
//                                println("   Relay URL: $url")
//                                println("   Message length: ${messageText.length} chars")
//                                println("   Message preview: ${messageText.take(100)}${if (messageText.length > 100) "..." else ""}")
//                                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

                                listener.onMessage(url, messageText)

                                println("✅ KtorWebSocketClient: Message handler completed for $url, Message: $messageText")
                            } catch (e: Exception) {
                                // Continue processing even if message handler fails
                                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                                println("❌ KtorWebSocketClient: Message processing error")
                                println("   Relay URL: $url")
                                println("   Error: ${e.message}")
                                println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                                e.printStackTrace()
                                listener.onFailure(url, e)
                            }
                        }

                        is Frame.Close -> {
                            val closeReason = frame.readReason()
                            val code = closeReason?.code?.toInt() ?: 1000
                            val reason = closeReason?.message ?: "Unknown"
                            println("KtorWebSocketClient: 🔌 Connection closing for $url: code=$code, reason=$reason")
                            listener.onClosing(url, code, reason)
                            listener.onClosed(url, code, reason)
                            connection.session = null
                            return@launch
                        }

                        else -> {
                            // Ignore other frame types (binary, ping, pong, etc.)
                        }
                    }
                }
            } catch (e: Exception) {
                println("KtorWebSocketClient: ❌ Connection failed for $url: ${e.message}")
                println("KtorWebSocketClient: Exception type: ${e::class.simpleName}")
                e.printStackTrace()
                connection.session = null
                listener.onFailure(url, e)
            } finally {
                if (session != null && connection.session != session) {
                    session?.close(CloseReason(1000, "Session closed"))
                }
                if (connection.session == session) {
                    connection.session = null
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
                    backoffMultiplier = backoffMultiplier
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
            activeConnections[url]?.session = null
        }
    }

    /**
     * Disconnect from a WebSocket URL.
     */
    suspend fun disconnect(url: String) {
        val connection = activeConnections.remove(url) ?: return
        connection.job?.cancel()
        connection.reconnectJob?.cancel()

        try {
            httpClient.webSocket(url) {
                close(CloseReason(1000, "Normal closure"))
            }
        } catch (e: Exception) {
            // Already closed or connection failed
        }
    }

    /**
     * Check if connected to a WebSocket URL.
     */
    fun isConnected(url: String): Boolean {
        return activeConnections[url]?.session?.isActive == true
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
