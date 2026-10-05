package com.bitchat.tui.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.tor.model.TorMode
import com.bitchat.tui.ChatScreen
import com.bitchat.tui.DmPhase
import com.bitchat.tui.DmScreen
import com.bitchat.tui.DmSession
import com.bitchat.tui.LatestOnly
import com.bitchat.tui.LineEditor
import com.bitchat.tui.LocalConsoleSafe
import com.bitchat.tui.LocalTuiTheme
import com.bitchat.tui.LocationsScreen
import com.bitchat.tui.MediaSizes
import com.bitchat.tui.Mode
import com.bitchat.tui.PeerEntry
import com.bitchat.tui.PeerTransport
import com.bitchat.tui.PeersScreen
import com.bitchat.tui.SettingsScreen
import com.bitchat.tui.TuiApp
import com.bitchat.tui.TuiNavigation
import com.bitchat.tui.WipeScreen
import com.bitchat.tui.chatPeople
import com.bitchat.tui.channelTitle
import com.bitchat.tui.channelUsesNostr
import com.bitchat.tui.commandFailureMessage
import com.bitchat.tui.dmChannelFor
import com.bitchat.tui.dmPeerFor
import com.bitchat.tui.eraseDrafts
import com.bitchat.tui.noteMessages
import com.bitchat.tui.peerEntries
import com.bitchat.tui.peopleCount
import com.bitchat.tui.torBlockedNotice
import com.bitchat.tui.sendOrKeep
import com.bitchat.tui.tuiTheme
import com.bitchat.viewmodel.navigation.Back
import com.bitchat.viewvo.chat.channelCommandSuggestions
import com.bitchat.viewmodel.navigation.Chat
import com.bitchat.viewmodel.navigation.LocationNotes
import com.bitchat.viewmodel.navigation.Locations
import com.bitchat.viewmodel.navigation.Settings
import com.jakewharton.mosaic.LocalTerminalState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The binding: collects the view models' state and feeds the `presentation/tui` screens, and turns
 * their callbacks into view-model calls. Everything runs on the composition's (the UI) thread,
 * which is also where [TuiNavigation.mode] must be set.
 *
 * DMs go through a [DmSession]: the view model switches its active chat in another coroutine, so
 * DM requests run one at a time in order, a DM is shown at once but takes lines only once its own
 * request has finished and the view model reports it selected, and a line is sent to the DM
 * channel itself ([com.bitchat.viewmodel.chat.DmViewModel.sendTo]), never to whatever chat is active.
 *
 * @param background Where work that outlives a screen runs (the Locations view model's teardown,
 *   geohash sampling).
 * @param notices Log problems; the latest is shown in the chat's error row until a line is sent.
 */
@Composable
fun BitchatTui(vms: TuiViewModels, background: CoroutineScope, notices: StateFlow<String?>) {
    val navigation = remember { TuiNavigation(Mode.Chat) }
    val header by vms.main.headerState.collectAsState()
    val chat by vms.chat.state.collectAsState()
    val dm by vms.dm.state.collectAsState()
    val settings by vms.settings.state.collectAsState()
    val appTheme by vms.main.appTheme.collectAsState()
    val chatEditor = remember { LineEditor() }
    // The place whose notes the Notes screen shows, set by the Locations screen that opened it.
    var notesGeohash by remember { mutableStateOf("") }
    // Nothing reaches a relay while this is set; the user has to switch Tor off themselves.
    val torBlocked = torBlockedNotice(settings.torAvailability, settings.requestedTorMode == TorMode.ON)
    val dmEditor = remember { LineEditor() }
    val chatMedia = remember { MediaSizes(::fileSize) }
    val dmMedia = remember { MediaSizes(::fileSize) }
    val notice by notices.collectAsState()
    var dismissedNotice by remember { mutableStateOf<String?>(null) }
    val ui = rememberCoroutineScope()
    val session = remember(vms) {
        DmSession(
            navigation, ui,
            start = { peer -> startDm(vms, peer) },
            // Leaves only that DM (or repairs a half-applied switch to it), never moving a channel.
            leave = { key -> vms.main.leaveDm(key).join() },
            describe = { key -> dmPeerFor(vms.main.headerState.value, key) },
        )
    }
    // One sampler for the process: a visit's "stop" cannot overtake the next visit's "start".
    val sampler = remember(vms) { LatestOnly(background, vms.sampleGeohashes) }

    // The view model's selected private peer drives the session (opening, late arrivals, leaving).
    LaunchedEffect(vms, session) {
        vms.main.headerState.map { it.selectedPrivatePeer }.distinctUntilChanged().collect { session.onSelectedPeer(it) }
    }

    // MainViewModel's navigation: the session decides between the chat and DM screens; the other
    // destinations have their own keys here. The channel is a rendezvous, so it must be read.
    LaunchedEffect(vms) {
        for (event in vms.main.navigation) {
            when (event) {
                is Chat -> navigation.mode = session.modeForChat(navigation.mode)
                Locations -> navigation.mode = Mode.Locations
                Settings -> navigation.mode = Mode.Settings
                Back -> Unit // Screens are left with the TUI's own keys.
                // Location Notes need a fix at building precision, which the Pi never has: go back to the chat.
                LocationNotes -> vms.main.goBack()
                else -> println("TUI: no screen for $event")
            }
        }
    }

    // A failed slash command becomes a system line in the chat, as in the Compose chat screen.
    LaunchedEffect(chat.pendingCommandFailure) {
        chat.pendingCommandFailure?.let { vms.chat.postCommandFailure(it, commandFailureMessage(it.failure)) }
    }

    fun openDm(peer: PeerEntry) {
        if (canStartDm(vms, peer)) session.open(peer)
    }

    /** Sends [line] in the open DM, or puts it back in the editor when the DM cannot take it now. */
    fun sendDm(line: String) {
        val now = vms.main.headerState.value
        val key = (session.phase as? DmPhase.Open)?.key
        val channel = key?.takeIf { session.canSend(now.selectedPrivatePeer) }?.let { dmChannelFor(now, it) }
        if (channel == null) {
            dmEditor.insert(line)
            return
        }
        sendOrKeep(dmEditor, line) { vms.dm.sendTo(channel, line) }
    }

    // The colours the user chose, live: changing the theme redraws every screen. A terminal that
    // does not say what its own colours are (the Pi console) counts as dark.
    val terminalTheme = LocalTerminalState.current.theme
    val consoleSafe = LocalConsoleSafe.current
    val theme = remember(appTheme, terminalTheme, consoleSafe) { tuiTheme(appTheme, terminalTheme, consoleSafe) }
    CompositionLocalProvider(LocalTuiTheme provides theme) {
        TuiApp(
            nickname = header.nickname,
            peerCount = peopleCount(header),
            navigation = navigation,
            unreadDms = dm.unreadPeers.size,
            // The terminal's answer to the Compose shield: with it off there is nothing on screen
            // that says proof of work is on at all.
            powBits = header.powDifficulty.takeIf { header.powEnabled && it > 0 },
        ) { mode, size ->
            when (mode) {
                Mode.Chat -> ChatScreen(
                    messages = chat.messages,
                    nickname = chat.nickname,
                    size = size,
                    onSend = { line ->
                        dismissedNotice = notice
                        // A /msg line may open a DM, which is then the user's and adopted.
                        session.onChatLine(line)
                        vms.chat.updateMessageInput(line)
                        // Handles /commands; the TUI never interprets them. A refused line
                        // (the user's data is being wiped) goes back in the prompt.
                        sendOrKeep(chatEditor, line) { vms.chat.sendMessage() }
                    },
                    // From the chat view model, like the list below it: the two can never name different chats.
                    title = channelTitle(chat.selectedLocationChannel),
                    titleColor = theme.channel(chat.selectedLocationChannel),
                    editor = chatEditor,
                    mediaSizes = remember(chat.messages) { chatMedia.of(chat.messages) },
                    // The Tor dead end outlives any one notice: it is there until Tor goes off.
                    // Only where it bites, though: the mesh and its channels never touch a relay.
                    errorMessage = chat.errorMessage
                        ?: torBlocked?.takeIf { channelUsesNostr(chat.selectedLocationChannel) }
                        ?: notice?.takeIf { it != dismissedNotice },
                    formatTime = ::localClockTime,
                    commands = remember(chat.selectedLocationChannel, chat.currentChannel) {
                        chat.selectedLocationChannel.channelCommandSuggestions(chat.currentChannel)
                    },
                    // Who `/hug ` and `/msg ` can be completed to: the peers screen's own list.
                    people = remember(header) { chatPeople(header) },
                )
                Mode.Peers -> PeersScreen(
                    peers = peerEntries(header, dm.unreadPeers),
                    size = size,
                    onOpenDm = ::openDm,
                    onToggleFavorite = { vms.main.toggleFavorite(it.id) },
                )
                Mode.Dm -> {
                    val phase = session.phase
                    val (peer, key) = when (phase) {
                        is DmPhase.Open -> phase.peer to phase.key
                        is DmPhase.Opening -> phase.peer to phase.key
                        is DmPhase.Failed -> phase.peer to phase.key
                        DmPhase.Closed -> null to null
                    }
                    val messages = key?.let { dm.privateChats[it] }.orEmpty()
                    DmScreen(
                        peerName = peer?.name.orEmpty(),
                        messages = messages,
                        nickname = chat.nickname,
                        size = size,
                        onSend = ::sendDm,
                        editor = dmEditor,
                        mediaSizes = remember(messages) { dmMedia.of(messages) },
                        // A mesh DM is radio all the way, so the Tor gate cannot be what stops it.
                        errorMessage = session.error
                            ?: dm.errorMessage
                            ?: torBlocked?.takeIf { peer?.transport == PeerTransport.Nostr },
                        formatTime = ::localClockTime,
                        sendHold = { session.sendHold },
                    )
                }
                Mode.Locations -> LocationsBody(vms, background, sampler, navigation, size) { geohash ->
                notesGeohash = geohash
                navigation.mode = Mode.Notes
            }
            Mode.Notes -> NotesBody(vms, background, notesGeohash, chat.nickname, size)
            Mode.Wipe -> WipeScreen(
                size = size,
                // The same call the Compose apps' triple tap makes, barrier and all.
                onConfirm = {
                    vms.main.handleTripleClick()
                    // The prompts are the app's own memory of what was typed, and go with the rest.
                    eraseDrafts(chatEditor, dmEditor)
                    navigation.mode = Mode.Chat
                },
                onCancel = { navigation.mode = Mode.Chat },
            )
                Mode.Settings -> SettingsScreen(
                    state = settings,
                    size = size,
                    onLoRaEnabled = vms.settings::onLoRaEnabledToggled,
                    onLoRaProtocol = vms.settings::onLoRaProtocolSelected,
                    onLoRaRegion = vms.settings::onLoRaRegionSelected,
                    onLoRaTxPower = vms.settings::onLoRaTxPowerSelected,
                    onLoRaShowPeers = vms.settings::onLoRaShowPeersToggled,
                    onTor = vms.settings::onTorNetworkToggled,
                    onProofOfWork = vms.settings::onProofOfWorkToggled,
                    onPowDifficulty = vms.settings::onPowDifficultyChanged,
                    onTheme = vms.settings::onThemeSelected,
                )
            }
        }
    }
}

/**
 * The Locations screen with a view model for this visit only (see [ScopedLocations]): created when
 * the screen is shown, released when it goes, so its five-second location poll runs only while
 * the screen is up; a selection made as it goes still completes. The nearby and bookmarked
 * geohashes on screen are sampled for participant counts meanwhile, as the Compose sheet does,
 * through the process-wide [sampler], and sampling stops when the screen goes.
 */
@Composable
private fun LocationsBody(
    vms: TuiViewModels,
    background: CoroutineScope,
    sampler: LatestOnly<List<String>>,
    navigation: TuiNavigation,
    size: com.jakewharton.mosaic.ui.unit.IntSize,
    onOpenNotes: (String) -> Unit,
) {
    val scoped = remember { ScopedLocations(vms.newLocations, background) }
    DisposableEffect(scoped) {
        onDispose {
            sampler.request(emptyList())
            scoped.release()
        }
    }
    val places by scoped.viewModel.state.collectAsState()
    val sampled = remember(places.availableChannels, places.bookmarkedGeohashes) {
        (places.availableChannels.map { it.geohash } + places.bookmarkedGeohashes)
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
    }
    LaunchedEffect(sampled) { sampler.request(sampled) }
    LocationsScreen(
        state = places,
        size = size,
        onSelectMesh = {
            scoped.act { onSelectMesh() }
            navigation.mode = Mode.Chat
        },
        onSelectChannel = { channel ->
            scoped.act { onSelectChannel(channel) }
            navigation.mode = Mode.Chat
        },
        onToggleBookmark = { geohash -> scoped.act { onToggleBookmark(geohash) } },
        // onMapResult validates, picks the level and teleports in one call.
        onTeleport = { geohash ->
            scoped.act { onMapResult(geohash) }
            navigation.mode = Mode.Chat
        },
        onOpenNotes = onOpenNotes,
    )
}

/**
 * The notes left at [geohash], as a conversation (see `noteMessages`), with its own view model for
 * this visit (see [ScopedNotes]) so the relay subscription goes when the screen does. The notes are
 * the place's, not the building the device stands in: this host has no location source at all.
 */
@Composable
private fun NotesBody(
    vms: TuiViewModels,
    background: CoroutineScope,
    geohash: String,
    nickname: String,
    size: com.jakewharton.mosaic.ui.unit.IntSize,
) {
    val scoped = remember(geohash) { ScopedNotes({ vms.newNotes(geohash) }, background) }
    DisposableEffect(scoped) { onDispose { scoped.release() } }
    val notes by scoped.viewModel.state.collectAsState()
    val editor = remember(geohash) { LineEditor() }
    ChatScreen(
        messages = remember(notes.notes) { noteMessages(notes.notes) },
        nickname = nickname,
        size = size,
        onSend = { line ->
            // The prompt clears on Enter, before the view model has had a chance to refuse: a note
            // typed before the place is resolved goes back where it was typed.
            sendOrKeep(editor, line) {
                var posted = false
                scoped.act {
                    onInputTextChange(line)
                    posted = onSendNote()
                }
                posted
            }
        },
        title = "Notes for #$geohash" + (notes.locationName?.let { " ($it)" } ?: ""),
        titleColor = LocalTuiTheme.current.geohash,
        editor = editor,
        errorMessage = notes.errorMessage,
        formatTime = ::localClockTime,
    )
}

/** Whether a DM with [peer] can be started: there is a view-model call for its transport and what it needs. */
private fun canStartDm(vms: TuiViewModels, peer: PeerEntry): Boolean = when (peer.transport) {
    // A mesh peer or a saved private chat, whatever radio it is on right now.
    PeerTransport.Direct, PeerTransport.DirectLoRa, PeerTransport.Routed, PeerTransport.Offline -> true
    PeerTransport.Nostr -> vms.main.headerState.value.geohashPeople.any { it.id == peer.id }
    PeerTransport.LoRa -> false // No view-model API for LoRa DMs yet.
}

/**
 * Asks the view model to switch to a DM with [peer] (see [canStartDm]) and waits for it: true
 * when the switch was made, false when it failed or there was nothing to start.
 */
private suspend fun startDm(vms: TuiViewModels, peer: PeerEntry): Boolean {
    val header = vms.main.headerState.value
    val job = when (peer.transport) {
        PeerTransport.Direct, PeerTransport.DirectLoRa, PeerTransport.Routed,
        PeerTransport.Offline -> vms.main.startMeshDM(peer.id, peer.name)
        PeerTransport.Nostr -> {
            val person = header.geohashPeople.firstOrNull { it.id == peer.id } ?: return false
            vms.main.startGeohashDM(person, (header.selectedLocationChannel as? Channel.Location)?.geohash)
        }
        PeerTransport.LoRa -> return false
    }
    // Never cancelled: a switch cut halfway would leave the view model inconsistent. The session
    // stops waiting instead, and undoes a late switch it no longer wants.
    job.join()
    return !job.isCancelled
}
