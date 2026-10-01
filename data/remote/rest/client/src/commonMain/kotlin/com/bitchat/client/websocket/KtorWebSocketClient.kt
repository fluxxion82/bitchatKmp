package com.bitchat.client.websocket

import com.bitchat.client.TorRouteProvenance
import com.bitchat.client.WebSocketRouteProvider
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.internal.SynchronizedObject
import kotlinx.coroutines.internal.synchronized
import kotlin.math.pow
import kotlin.time.Duration.Companion.seconds

/** One controller serializes all mutable state for one URL. */
@OptIn(InternalCoroutinesApi::class)
internal class KtorWebSocketClient(private val routeProvider: WebSocketRouteProvider) {
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + scopeJob)
    private val registryLock = SynchronizedObject()
    private val controllers = mutableMapOf<String, Controller>()
    private var terminal = false

    /** The one [shutdown] operation; later callers join it instead of running their own. */
    private var shuttingDown: Job? = null

    /** Outlives [scope], which [shutdown] cancels, and belongs to no caller that may be cancelled. */
    private val lifecycleScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** Synchronous snapshot; only its owning [Controller] mutates lifecycle state. */
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
        var route: TorRouteProvenance? = null,
        internal var reconnectPolicy: ReconnectPolicy? = null,
        internal var owner: Any? = null,
        /** The newest owner allowed to reach listener code. */
        internal var callbackOwner: Any? = null,
    )

    private sealed interface Command {
        data class Connect(val policy: ReconnectPolicy, val resetBudget: Boolean) : Command
        data class Opened(val owner: Any, val session: WebSocketSession, val route: TorRouteProvenance) : Command
        data class ReaderEnded(val owner: Any, val session: WebSocketSession?, val error: Throwable?) : Command
        data class Message(val owner: Any, val session: WebSocketSession, val text: String) : Command
        data class SendRequested(val message: String, val completed: CompletableDeferred<Unit>) : Command
        data class SendResult(val owner: Any, val session: WebSocketSession, val error: Throwable?, val completed: CompletableDeferred<Unit>) : Command
        data class Disconnect(val completed: CompletableDeferred<Unit>) : Command
        data class RetryDue(val token: Any, val policy: ReconnectPolicy) : Command
        data class Shutdown(val completed: CompletableDeferred<Unit>) : Command
    }

    private data class Callback(val url: String, val owner: Any, val invoke: () -> Unit)
    private val callbacks = Channel<Callback>(Channel.UNLIMITED)
    private val callbackDispatcher = scope.launch {
        for (callback in callbacks) {
            // Callbacks are ordered here and rejected again immediately before NostrRelay can see them.
            if (isCallbackCurrent(callback.url, callback.owner)) runCatching(callback.invoke)
        }
    }

    fun connect(
        url: String,
        listener: WebSocketListener,
        maxReconnectAttempts: Int = 10,
        initialBackoffMs: Long = 1000L,
        maxBackoffMs: Long = 60000L,
        backoffMultiplier: Double = 2.0,
        resetBudget: Boolean = true,
    ) {
        controller(url)?.commands?.trySend(
            Command.Connect(ReconnectPolicy(listener, maxReconnectAttempts, initialBackoffMs, maxBackoffMs, backoffMultiplier), resetBudget),
        )
    }

    suspend fun send(url: String, message: String) {
        val completion = CompletableDeferred<Unit>()
        val controller = controller(url) ?: return
        // A shutdown between taking the controller and enqueueing closes the channel: nothing will
        // ever answer a rejected request, so do not wait for an answer.
        if (controller.commands.trySend(Command.SendRequested(message, completion)).isSuccess) completion.await()
    }

    suspend fun disconnect(url: String) {
        val completion = CompletableDeferred<Unit>()
        val controller = synchronized(registryLock) { controllers[url] } ?: return
        if (controller.commands.trySend(Command.Disconnect(completion)).isSuccess) completion.await()
    }

    fun isConnected(url: String): Boolean = synchronized(registryLock) {
        activeConnections[url]?.let { it.session?.isActive == true && it.route?.isCurrent() == true } == true
    }

    fun isConnecting(url: String): Boolean = synchronized(registryLock) {
        activeConnections[url]?.let { it.job?.isActive == true && it.session == null } == true
    }

    /**
     * Terminal external-owner operation: closes sessions, cancels every controller/worker and
     * joins the client scope. A descendant must request this from outside its own scope instead
     * of awaiting itself.
     */
    suspend fun shutdown() {
        // One shutdown operation, awaited by every caller, running in a scope of its own. It must not
        // belong to any caller: a caller cancelled midway would otherwise leave the registry populated
        // and the worker scope alive while later callers returned as if shutdown had finished. It also
        // must not belong to [scope], which this very operation cancels.
        val running = synchronized(registryLock) {
            shuttingDown ?: lifecycleScope.launch(start = CoroutineStart.LAZY) { runShutdown() }
                .also { shuttingDown = it }
        }
        running.start()
        running.join()
    }

    private suspend fun runShutdown() {
        val pending = synchronized(registryLock) {
            terminal = true
            activeConnections.values.forEach { it.callbackOwner = null }
            activeConnections.clear()
            controllers.values.toList()
        }
        val settled = pending.map { controller ->
            CompletableDeferred<Unit>().also { controller.commands.trySend(Command.Shutdown(it)) }
        }
        settled.forEach { it.await() }
        callbacks.close()
        scopeJob.cancel()
        scopeJob.join()
        synchronized(registryLock) {
            controllers.clear()
            activeConnections.clear()
        }
    }

    private fun controller(url: String): Controller? = synchronized(registryLock) {
        if (terminal) return@synchronized null
        controllers.getOrPut(url) {
            // Registration and terminal admission share the registry lock.
            val snapshot = activeConnections[url] ?: WebSocketConnection(url).also { activeConnections[url] = it }
            Controller(url, snapshot)
        }
    }

    private fun isCallbackCurrent(url: String, owner: Any): Boolean = synchronized(registryLock) {
        !terminal && activeConnections[url]?.callbackOwner === owner
    }

    private inner class Controller(private val url: String, private val connection: WebSocketConnection) {
        val commands = Channel<Command>(Channel.UNLIMITED)
        val job = scope.launch { for (command in commands) handle(command) }

        private var owner: Any? = connection.owner
        private var session: WebSocketSession? = connection.session
        private var route: TorRouteProvenance? = connection.route
        private var worker: Job? = null
        private var retry: Job? = null
        private var retryToken: Any? = null
        private var policy: ReconnectPolicy? = connection.reconnectPolicy
        private var explicitlyDisconnected = false

        /** Completions of writes handed to [scope], owed a result. Touched only on this controller. */
        private val pendingSends = mutableListOf<CompletableDeferred<Unit>>()

        private suspend fun handle(command: Command) {
            when (command) {
                is Command.Connect -> connect(command.policy, command.resetBudget)
                is Command.Opened -> opened(command)
                is Command.ReaderEnded -> readerEnded(command)
                is Command.Message -> message(command)
                is Command.SendRequested -> send(command)
                is Command.SendResult -> sendResult(command)
                is Command.Disconnect -> {
                    explicitlyDisconnected = true
                    policy = null
                    retire(false)
                    removeSnapshot()
                    command.completed.complete(Unit)
                }
                is Command.RetryDue -> if (!explicitlyDisconnected && retryToken === command.token && owner == null && session == null) {
                    retry = null
                    retryToken = null
                    startWorker(command.policy)
                }
                is Command.Shutdown -> {
                    explicitlyDisconnected = true
                    policy = null
                    retire(false)
                    // Closing the channel and cancelling the scope means no SendResult can arrive for a
                    // write still in flight, so release its caller here instead of leaving it awaiting.
                    pendingSends.forEach { it.complete(Unit) }
                    pendingSends.clear()
                    commands.close()
                    command.completed.complete(Unit)
                }
            }
        }

        private fun connect(newPolicy: ReconnectPolicy, resetBudget: Boolean) {
            explicitlyDisconnected = false
            policy = newPolicy
            if (resetBudget) connection.reconnectAttempts = 0
            if (session?.isActive == true && route?.isCurrent() == true) {
                publishSnapshot()
                return
            }
            if (owner != null && (session == null || session?.isActive == true)) return // duplicate Connect during a dial/live read
            retire(false)
            startWorker(newPolicy)
        }

        private fun startWorker(newPolicy: ReconnectPolicy) {
            if (explicitlyDisconnected || terminal) return
            retry?.cancel()
            retry = null
            retryToken = null
            val newOwner = Any()
            owner = newOwner // Ownership exists before dial: failures before publication are owner-based.
            session = null
            route = null
            publishSnapshot()
            worker = scope.launch {
                var opened: WebSocketSession? = null
                var reported = false
                try {
                    // This route-owning worker spans dial, reader, close, and provider engine retirement.
                    routeProvider.useWebSocketRoute { client, leasedRoute ->
                        opened = withTimeoutOrNull(15.seconds) { client.webSocketSession(url) }
                        val established = opened
                        if (established == null) {
                            commands.trySend(Command.ReaderEnded(newOwner, null, IllegalStateException("WebSocket connection timeout")))
                            reported = true
                            return@useWebSocketRoute
                        }
                        commands.trySend(Command.Opened(newOwner, established, leasedRoute))
                        for (frame in established.incoming) {
                            when (frame) {
                                is Frame.Text -> commands.trySend(Command.Message(newOwner, established, frame.readText()))
                                is Frame.Close -> {
                                    commands.trySend(Command.ReaderEnded(newOwner, established, null))
                                    reported = true
                                    return@useWebSocketRoute
                                }
                                else -> Unit
                            }
                        }
                        if (!reported) {
                            // Report reader completion before route-provider retirement can block.
                            commands.trySend(Command.ReaderEnded(newOwner, established, null))
                            reported = true
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    commands.trySend(Command.ReaderEnded(newOwner, opened, error))
                    reported = true
                } finally {
                    if (!reported) commands.trySend(Command.ReaderEnded(newOwner, opened, null))
                    opened?.let { stale -> runCatching { stale.close(CloseReason(CloseReason.Codes.NORMAL, "Session closed")) } }
                }
            }
            publishSnapshot()
        }

        private fun opened(command: Command.Opened) {
            if (owner !== command.owner || explicitlyDisconnected || !command.route.isCurrent()) {
                close(command.session, "Superseded")
                return
            }
            session = command.session
            route = command.route
            connection.reconnectAttempts = 0
            connection.callbackOwner = command.owner
            publishSnapshot()
            callback(command.owner) { policy?.listener?.onOpen(url, command.route) }
        }

        private fun readerEnded(command: Command.ReaderEnded) {
            if (owner !== command.owner || (command.session != null && session != null && session !== command.session)) {
                command.session?.let { close(it, "Superseded") }
                return
            }
            connection.callbackOwner = command.owner
            val listener = policy?.listener
            if (command.error != null) callback(command.owner) { listener?.onFailure(url, command.error) }
            session = null
            route = null
            owner = null
            worker = null
            publishSnapshot()
            if (!explicitlyDisconnected) scheduleRetry()
        }

        private fun message(command: Command.Message) {
            if (owner === command.owner && session === command.session && !explicitlyDisconnected) {
                callback(command.owner) { policy?.listener?.onMessage(url, command.text) }
            }
        }

        private fun send(command: Command.SendRequested) {
            // A request that queued behind Disconnect or Shutdown is answered here and nowhere else:
            // retire() leaves the published snapshot alone once terminal, so the fallback below could
            // still find a session, and its result could no longer come back through a closed channel.
            if (explicitlyDisconnected) {
                command.completed.complete(Unit)
                return
            }
            // The snapshot fallback preserves the old, intentionally injected JVM-session tests;
            // production writes these fields only from this controller.
            val capturedSession = session ?: connection.session
            if (capturedSession == null) {
                // Nothing to write on. Minting an owner here would leave this controller owned with
                // no worker and no session, which RetryDue and connect() both read as a dial already
                // in flight: the relay would then never reconnect.
                command.completed.complete(Unit)
                return
            }
            val capturedOwner = owner ?: connection.owner ?: Any().also {
                owner = it
                connection.owner = it
            }
            if ((route ?: connection.route)?.isCurrent() != true) {
                retire(true)
                command.completed.complete(Unit)
                return
            }
            pendingSends += command.completed
            scope.launch {
                val error = runCatching { capturedSession.send(Frame.Text(command.message)) }.exceptionOrNull()
                commands.trySend(Command.SendResult(capturedOwner, capturedSession, error, command.completed))
            }
        }

        private fun sendResult(command: Command.SendResult) {
            pendingSends -= command.completed
            if (command.error != null) {
                // A's send cannot retire B: both the owner and captured session must still match.
                if (connection.owner === command.owner && connection.session === command.session) retire(true)
                else close(command.session, "Obsolete send failure")
            }
            command.completed.complete(Unit)
        }

        private fun retire(scheduleRetry: Boolean) {
            val retiringSession = session
            owner = null
            session = null
            route = null
            (worker ?: connection.job)?.cancel()
            worker = null
            retry?.cancel()
            retry = null
            retryToken = null
            retiringSession?.let { close(it, "Retired") }
            publishSnapshot()
            if (scheduleRetry && !explicitlyDisconnected) scheduleRetry(force = true)
        }

        private fun scheduleRetry(force: Boolean = false) {
            val saved = policy ?: return
            if ((!force && connection.reconnectAttempts >= saved.maxReconnectAttempts) || retry?.isActive == true || terminal) return
            connection.reconnectAttempts++
            val backoff = (saved.initialBackoffMs * saved.backoffMultiplier.pow(connection.reconnectAttempts - 1.0))
                .toLong().coerceAtMost(saved.maxBackoffMs)
            val token = Any()
            retryToken = token
            retry = scope.launch {
                delay(backoff)
                commands.trySend(Command.RetryDue(token, saved))
            }
            publishSnapshot()
        }

        private fun close(session: WebSocketSession, reason: String) {
            scope.launch { runCatching { session.close(CloseReason(CloseReason.Codes.NORMAL, reason)) } }
        }

        private fun callback(callbackOwner: Any, block: () -> Unit) {
            callbacks.trySend(Callback(url, callbackOwner, block))
        }

        private fun publishSnapshot() = synchronized(registryLock) {
            if (!terminal && !explicitlyDisconnected) {
                connection.owner = owner
                connection.session = session
                connection.route = route
                connection.job = worker
                connection.reconnectJob = retry
                connection.reconnectPolicy = policy
                activeConnections[url] = connection
            }
        }

        private fun removeSnapshot() = synchronized(registryLock) {
            connection.callbackOwner = null
            activeConnections.remove(url)
        }
    }
}
