package com.bitchat.tui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bitchat.domain.chat.messageCommandTarget
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The key the view models file a DM with [peer] under (`HeaderState.selectedPrivatePeer`,
 * `DmState.privateChats`, `DmState.unreadPeers`): a mesh peer ID as is, a geohash person's full
 * Nostr key shortened to `nostr_` and its first 16 characters, as `SaveUserStateAction` does.
 */
fun dmConversationKey(peer: PeerEntry): String = dmConversationKey(peer.id, peer.transport)

fun dmConversationKey(id: String, transport: PeerTransport): String =
    if (transport == PeerTransport.Nostr) "nostr_${id.take(16)}" else id

/**
 * The DM the terminal UI shows, kept in step with a view model that switches its active chat
 * asynchronously and reports it through `selectedPrivatePeer`, a conflated state that can skip
 * values and lag behind.
 *
 * Requests to the view model ([start] a DM, [leave] one) are made one at a time, in order, each
 * waited for before the next is made, so leaving a DM and opening it again cannot complete the
 * wrong way round. No request may hold the queue: after [requestTimeout] the session stops
 * waiting and goes on, but never cancels it (a DM switch cut halfway leaves the view model
 * inconsistent); the view model must apply requests in the order they were made, each to its
 * end. A start that finishes after its wait was given up, for a DM no longer wanted, is undone
 * with a leave of that DM. [leave] (a conversation key) must leave only that DM, and do nothing,
 * beyond repairing a half-applied switch to it, when it is not the active chat.
 *
 * A DM opened here is [DmPhase.Opening] until its own start request has finished (the
 * acknowledgement, keyed by request generation) and the view model reports it selected; if the
 * view model already reports it by then, it is open at once. It becomes [DmPhase.Failed], shown as
 * [error], when its start fails or times out (a late start is then undone, see above), or when
 * the view model has not reported it [openTimeout] after the start finished; every wait is bounded. Leaving the DM screen, by any key, closes the DM synchronously (a
 * [TuiNavigation] mode listener, before the next key in the batch), drops its start if still
 * queued, and queues a leave behind anything pending.
 *
 * DMs the view model opens on its own: one addressed by a `/msg` (or `/m`) line the user sent
 * within [commandWindow] ([onChatLine]) is adopted as open when the view model reports a DM with
 * that nickname (described by [describe]), the latest such report winning; the one restored at
 * startup is left again (the TUI starts in a public chat); anything else is ignored. Reports that
 * arrive while requests are pending are judged again, against the latest report, once they finish.
 *
 * Runs on [scope]'s thread, which must be the UI thread, as must every call here.
 */
class DmSession(
    private val navigation: TuiNavigation,
    private val scope: CoroutineScope,
    private val start: suspend (PeerEntry) -> Boolean,
    private val leave: suspend (key: String) -> Unit,
    private val describe: (key: String) -> PeerEntry,
    private val openTimeout: Duration = 10.seconds,
    private val requestTimeout: Duration = 10.seconds,
    private val commandWindow: Duration = 10.seconds,
) {
    var phase: DmPhase by mutableStateOf(DmPhase.Closed)
        private set

    /** The view model's selected private peer, as last reported. */
    private var selected: String? by mutableStateOf(null)

    private var generation = 0

    /** Requests queued or running; while any is, the view model's reports are its own settling. */
    private var pending = 0

    /** Generations whose start request finished with the DM switched to. */
    private val started = HashSet<Int>()

    /** Generations given up on: their start is skipped if still queued. */
    private val abandoned = HashSet<Int>()

    /** Generations whose start was still running when the session stopped waiting for it. */
    private val late = HashSet<Int>()

    private var startupHandled = false

    /** Nicknames of `/msg` lines sent in the last [commandWindow], oldest first. */
    private val commandTargets = ArrayList<CommandTarget>()
    private var commandIds = 0

    private val requests = Channel<Request>(Channel.UNLIMITED)

    private class Request(val startOf: Int?, val body: suspend () -> Unit)

    private data class CommandTarget(val nickname: String, val id: Int)

    init {
        navigation.addModeListener { from, to -> if (from == Mode.Dm && to != Mode.Dm) close() }
        scope.launch {
            for (request in requests) {
                try {
                    val gen = request.startOf
                    if (gen != null && gen in abandoned) continue
                    val job = scope.launch {
                        try {
                            request.body()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // A failed request changes nothing here; a start's absence from started says so.
                        }
                    }
                    // Too slow: stop waiting, never cancel (see the class comment).
                    if (withTimeoutOrNull(requestTimeout) { job.join() } == null && gen != null) late += gen
                    if (gen != null && gen !in started) onStarted(gen, ok = false)
                } finally {
                    pending--
                    if (pending == 0) evaluate()
                }
            }
        }
    }

    /** Opens a DM with [peer]: queues its start and shows the DM screen, holding sends until it is open. */
    fun open(peer: PeerEntry) {
        startupHandled = true
        val gen = ++generation
        started.removeAll { it < gen - 16 } // Only recent requests can still be asked about.
        abandoned.removeAll { it < gen - 16 }
        late.removeAll { it < gen - 16 }
        val key = dmConversationKey(peer)
        phase = DmPhase.Opening(peer, key, gen)
        enqueue(Request(gen) {
            if (start(peer)) {
                started += gen
                onStarted(gen, ok = true)
                // Switched after the session gave up waiting: undo it unless this DM is wanted again.
                if (gen in late && !wants(key)) enqueue(Request(null) { leave(key) })
            }
        })
        navigation.mode = Mode.Dm
    }

    /**
     * The user sent [line] in the chat. A `/msg` or `/m` line (read as `ProcessChatCommand` reads
     * it) makes a DM with its nickname, if the view model reports one soon, the user's: adopted.
     */
    fun onChatLine(line: String) {
        startupHandled = true
        val nickname = messageCommandTarget(line) ?: return
        val target = CommandTarget(nickname, ++commandIds)
        commandTargets += target
        scope.launch {
            delay(commandWindow)
            commandTargets.remove(target)
        }
    }

    /** The view model's selected private peer changed to [peer] (null: a public chat). */
    fun onSelectedPeer(peer: String?) {
        selected = peer
        val current = phase
        if (current is DmPhase.Opening && current.generation in started && peer == current.key) {
            phase = DmPhase.Open(current.peer, current.key, current.generation)
        }
        evaluate()
    }

    /** The DM screen was left: stop wanting the DM, and queue its leave behind anything pending. */
    fun close() {
        val current = phase
        if (current == DmPhase.Closed) return
        phase = DmPhase.Closed
        if (current is DmPhase.Opening) abandoned += current.generation
        if (current is DmPhase.Failed) abandoned += current.generation
        // A start already running or done is left behind it; a leave with nothing to leave does nothing.
        val key = when (current) {
            is DmPhase.Opening -> current.key
            is DmPhase.Open -> current.key
            is DmPhase.Failed -> current.key
            DmPhase.Closed -> return
        }
        enqueue(Request(null) { leave(key) })
    }

    /**
     * Whether a line may be sent in the DM now: it is open and [selectedNow] (the view model's
     * current selected private peer) is it. The line then goes to that DM's own channel.
     */
    fun canSend(selectedNow: String?): Boolean = (phase as? DmPhase.Open)?.key?.let { it == selectedNow } == true

    /**
     * The screen to show for the view model's "show the chat": the DM screen while a DM is wanted
     * (or failed to open, so its error stays in view), the chat instead of a DM screen that is no
     * longer wanted, anything else unchanged (`Esc` from a DM has already gone to Peers).
     */
    fun modeForChat(current: Mode): Mode = when {
        phase != DmPhase.Closed -> Mode.Dm
        current == Mode.Dm -> Mode.Chat
        else -> current
    }

    /** What the DM screen's prompt shows instead of accepting `Enter`: null when a line can be sent. */
    val sendHold: String?
        get() = when (val current = phase) {
            is DmPhase.Open -> if (selected == current.key) null else "waiting... "
            is DmPhase.Opening -> "opening... "
            is DmPhase.Failed -> "not open "
            DmPhase.Closed -> "closed "
        }

    /** The error to show on the DM screen, if the DM could not be opened. */
    val error: String?
        get() = (phase as? DmPhase.Failed)?.let { "Couldn't open the DM with ${it.peer.name}" }

    private fun onStarted(gen: Int, ok: Boolean) {
        val current = phase
        if (current !is DmPhase.Opening || current.generation != gen) return
        when {
            !ok -> phase = DmPhase.Failed(current.peer, current.key, gen)
            selected == current.key -> phase = DmPhase.Open(current.peer, current.key, gen)
            // Switched: now the view model has [openTimeout] to report it.
            else -> scope.launch {
                delay(openTimeout)
                val now = phase
                if (now is DmPhase.Opening && now.generation == gen) phase = DmPhase.Failed(now.peer, now.key, gen)
            }
        }
    }

    /** Judges the latest report of the view model's DM, once no request of ours is pending. */
    private fun evaluate() {
        val peer = selected ?: return
        if (pending > 0) return
        val current = phase
        val adoptable = current == DmPhase.Closed ||
            (current is DmPhase.Open && current.generation == null && current.key != peer)
        if (!adoptable) return
        val who = describe(peer)
        // `/msg bob` finds a peer by what it announces now, and its DM may be shown under the name its
        // chat was opened under: either is who the command meant.
        val target = commandTargets.lastOrNull { command ->
            addressedTo(who.name, command.nickname) || who.claims?.let { addressedTo(it, command.nickname) } == true
        }
        when {
            target != null -> {
                commandTargets.remove(target)
                phase = DmPhase.Open(who, peer, generation = null)
                navigation.mode = Mode.Dm
            }
            current == DmPhase.Closed && !startupHandled -> {
                startupHandled = true
                enqueue(Request(null) { leave(peer) })
            }
        }
    }

    /** Whether the DM filed under [key] is the one shown or being opened. */
    private fun wants(key: String): Boolean = when (val current = phase) {
        is DmPhase.Opening -> current.key == key
        is DmPhase.Open -> current.key == key
        else -> false
    }

    /** Whether a peer shown as [name] is who a `/msg [nickname]` means, as `ChatViewModel` matches it. */
    private fun addressedTo(name: String, nickname: String): Boolean =
        name.equals(nickname, ignoreCase = true) || name.startsWith("$nickname#", ignoreCase = true)

    private fun enqueue(request: Request) {
        pending++
        requests.trySend(request)
    }
}

/**
 * Where a [DmSession] is. [key] is the [dmConversationKey] of [peer]; [generation] identifies the
 * open request (null for a DM adopted from the view model).
 */
sealed interface DmPhase {
    data object Closed : DmPhase

    data class Opening(val peer: PeerEntry, val key: String, val generation: Int) : DmPhase

    data class Open(val peer: PeerEntry, val key: String, val generation: Int?) : DmPhase

    data class Failed(val peer: PeerEntry, val key: String, val generation: Int) : DmPhase
}
