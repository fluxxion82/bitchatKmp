package com.bitchat.tui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.bitchat.domain.chat.model.BitchatMessage
import com.bitchat.domain.chat.model.MeshChannelPerson
import com.bitchat.domain.chat.model.MeshChannelTransport
import com.bitchat.viewvo.theme.peerColorSeed
import com.bitchat.domain.location.model.GeoPerson
import com.jakewharton.mosaic.layout.size
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.unit.IntSize
import kotlin.time.Instant

/** How a peer is reached, shown as a tag at the end of its row. */
enum class PeerTransport(val label: String) {
    /** A Bluetooth neighbour. */
    Direct("direct"),

    /** A Bluetooth neighbour also heard over LoRa. */
    DirectLoRa("direct+lora"),

    /** Reached through other mesh peers. */
    Routed("routed"),

    /** A geohash-channel participant, reached over Nostr. */
    Nostr("nostr"),

    /** Heard over the LoRa radio. */
    LoRa("lora"),

    /** A saved private chat whose other side is not connected over the mesh right now. */
    Offline("offline"),
}

/**
 * One row of [PeersScreen]. [id] is what a DM is opened with (a mesh peer ID, a Nostr key), except
 * for a row known only from the LoRa radio: its [id] only tells the rows apart, and [dmPeerId] is the
 * mesh peer ID its heartbeat announces (null when it announces none: nothing can be opened then).
 * [name] comes from the peer and is sanitized when drawn. [claims] is what the peer
 * announces now when its row shows the fixed name of its private chat; it comes from the peer and
 * is sanitized when drawn, like [name].
 */
data class PeerEntry(
    val id: String,
    val name: String,
    val transport: PeerTransport,
    val claims: String? = null,
    val favorite: Boolean = false,
    val unread: Boolean = false,
    val dmPeerId: String? = null,
)

private val LoRaPeerHints = listOf(
    KeyHint("Enter", "no DM over LoRa"),
    KeyHint("Up/Down", "select"),
    KeyHint("f", "favourite"),
    KeyHint("Esc", "back"),
    KeyHint("Tab", "next"),
)

/**
 * The row a private chat is opened from, as the chat it opens. A row known only from the radio stands
 * for the mesh peer id its heartbeat announces and for nothing else the heartbeat says: the chat is
 * known by its own name when there is one already and by the start of its id otherwise, never by the
 * name in the heartbeat, which anybody in range can send. Every other row is the chat it opens.
 */
fun dmTarget(row: PeerEntry, peerNicknames: Map<String, String>): PeerEntry {
    val peerId = row.dmPeerId?.takeIf { row.transport == PeerTransport.LoRa } ?: return row
    return PeerEntry(id = peerId, name = peerNicknames[peerId] ?: peerId.take(12), transport = PeerTransport.LoRa, dmPeerId = peerId)
}

internal fun peersFooterHints(selected: PeerEntry?): List<KeyHint>? =
    LoRaPeerHints.takeIf { selected?.transport == PeerTransport.LoRa && selected.dmPeerId == null }

/**
 * The mesh channel's people (`HeaderState.meshPeople`), one row per device. The rows a private chat
 * can be opened from are sorted like the Compose sidebar (unread DMs first, then favourites, then by
 * name); the peers known only from the LoRa radio follow in the order they were heard.
 */
fun meshChannelPeerEntries(
    people: List<MeshChannelPerson>,
    peerNicknames: Map<String, String>,
    favoritePeers: Set<String>,
    unreadPeers: Set<String>,
): List<PeerEntry> {
    fun entry(person: MeshChannelPerson): PeerEntry = PeerEntry(
        id = person.id,
        // The name `HeaderState.peerNicknames` settles on, as the Compose sidebar shows it; a peer
        // known only from the radio is not in that directory and has the name it broadcasts.
        name = peerNicknames[person.id]?.takeUnless { person.isLoRaOnly } ?: person.displayName,
        transport = person.transport(),
        claims = person.claimedName,
        favorite = person.id in favoritePeers,
        unread = person.id in unreadPeers,
        dmPeerId = person.dmPeerId,
    )

    val listed = people.filterNot { it.isLoRaOnly }.map(::entry)
        .sortedWith(compareBy<PeerEntry>({ !it.unread }, { !it.favorite }, { it.name.lowercase() }))
    val loRaOnly = people.filter { it.isLoRaOnly }.map(::entry)
    return listed + loRaOnly
}

/**
 * The tag a mesh person's row ends with. Every tag but [PeerTransport.LoRa] is a row a private chat
 * can be opened from (it has a mesh peer id or a conversation key); a peer known only from the radio
 * can be when its heartbeat announces a mesh peer id ([PeerEntry.dmPeerId], see [dmTarget]).
 * A saved Nostr conversation keeps the `routed` tag it has always had
 * in this list: [PeerTransport.Nostr] means a geohash participant, which it is not.
 */
internal fun MeshChannelPerson.transport(): PeerTransport {
    val onMesh = MeshChannelTransport.MESH in transports
    return when {
        onMesh && MeshChannelTransport.LORA in transports -> PeerTransport.DirectLoRa
        onMesh -> PeerTransport.Direct
        isLoRaOnly -> PeerTransport.LoRa
        id.startsWith("nostr_") -> PeerTransport.Routed
        else -> PeerTransport.Offline
    }
}

internal fun legacyMeshPeerEntries(
    connectedPeers: List<String>,
    peerNicknames: Map<String, String>,
    peerDirect: Map<String, Boolean>,
    favoritePeers: Set<String>,
    unreadPeers: Set<String>,
): List<PeerEntry> = connectedPeers
    .map { id ->
        PeerEntry(
            id = id,
            name = peerNicknames[id] ?: id.take(12),
            transport = if (peerDirect[id] == true) PeerTransport.Direct else PeerTransport.Routed,
            favorite = id in favoritePeers,
            unread = id in unreadPeers,
        )
    }
    .sortedWith(compareBy<PeerEntry>({ !it.unread }, { !it.favorite }, { it.name.lowercase() }))

/**
 * Geohash-channel people (`ChatState.geohashPeople`) or LoRa peers (`HeaderState.loraPeers`).
 * [unreadPeers] holds conversation keys (`DmState.unreadPeers`), matched through [dmConversationKey]:
 * a geohash person's DM is filed under a shortened form of the full key it is listed with.
 */
fun geoPeerEntries(
    people: List<GeoPerson>,
    transport: PeerTransport,
    favoritePeers: Set<String> = emptySet(),
    unreadPeers: Set<String> = emptySet(),
): List<PeerEntry> = people.map {
    PeerEntry(
        it.id, it.displayName, transport,
        favorite = it.id in favoritePeers,
        unread = dmConversationKey(it.id, transport) in unreadPeers,
    )
}

/**
 * The people list: a title row, then one full-width row per peer (`!` unread DM, `*` favourite,
 * the name, and the transport tag on the right). The selected row is in reverse video; other rows
 * with unread DMs are bold. The list scrolls to keep the selection visible.
 *
 * Keys: `Up`/`Down` select, `Enter` passes the selected peer to [onOpenDm], `f` to
 * [onToggleFavorite]. `Esc` is left to the shell. The selection is a peer, not a row: it starts on
 * the first peer shown and follows that peer when the list reorders; when the selected peer
 * leaves, the peer now in its row (or the new last one) is selected.
 */
@Composable
fun PeersScreen(
    peers: List<PeerEntry>,
    size: IntSize,
    onOpenDm: (PeerEntry) -> Unit,
    onToggleFavorite: (PeerEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val consoleSafe = LocalConsoleSafe.current
    val theme = LocalTuiTheme.current
    val selection = remember { PeerSelection() }
    val selected = selection.resolve(peers)
    FooterHints(peersFooterHints(peers.getOrNull(selected)))
    // Rows by priority: the selected peer (or the empty note), the title, the rest of the list.
    val budget = RowBudget(size.height)
    val listMinimum = budget.take(1)
    val titleRows = budget.take(1)
    val listRows = listMinimum + budget.take(peers.size - 1)
    val first = (selected - listRows + 1).coerceAtLeast(0)

    Column(
        modifier
            .size(size.width, size.height)
            .screenKeys { event ->
                if (event.ctrl || event.alt || peers.isEmpty()) return@screenKeys false
                // Read the selection now, not at composition: Mosaic delivers every key that
                // arrived since the last frame before recomposing.
                val current = selection.resolve(peers)
                when (event.key) {
                    "ArrowUp" -> selection.select(peers, (current - 1).coerceAtLeast(0))
                    "ArrowDown" -> selection.select(peers, (current + 1).coerceAtMost(peers.lastIndex))
                    "Enter" -> onOpenDm(peers[current])
                    "f" -> onToggleFavorite(peers[current])
                    else -> return@screenKeys false
                }
                true
            },
    ) {
        if (titleRows > 0) Text(" People (${peers.size})".truncateCells(size.width), color = theme.accent, textStyle = TextStyle.Bold)
        if (peers.isEmpty() && listRows > 0) {
            Text(" no one connected".truncateCells(size.width), color = theme.dim, textStyle = TextStyle.Dim)
        }
        for (index in first until (first + listRows).coerceAtMost(peers.size)) {
            val peer = peers[index]
            val row = peerRow(peer, size.width, consoleSafe)
            when {
                index == selected -> Bar(row, size.width)
                peer.unread -> Bar(row, size.width, textStyle = TextStyle.Bold)
                // The colour the chat gives this peer, so a name is the same colour in both.
                else -> Text(row, color = theme.peer(peerColorSeed(peer.id, peer.name)))
            }
        }
    }
}

/** The selected peer, by ID, and the row it was last seen in. */
private class PeerSelection {
    var id by mutableStateOf<String?>(null)
    private var row = 0

    /** The selected row in [peers], storing the fallback choice when the peer is gone or none was made. */
    fun resolve(peers: List<PeerEntry>): Int {
        if (peers.isEmpty()) return 0
        var at = peers.indexOfFirst { it.id == id }
        if (at < 0) {
            at = row.coerceIn(0, peers.lastIndex)
            id = peers[at].id
        }
        row = at
        return at
    }

    fun select(peers: List<PeerEntry>, at: Int) {
        id = peers[at].id
        row = at
    }
}

/**
 * A peer row, at most [width] cells: marks and name on the left, the transport tag on the right.
 * The name is sanitized (and made console-safe) before it is measured; a long name is ellipsized,
 * and when even six cells of it no longer fit, the tag goes.
 *
 * What the peer announces now ([PeerEntry.claims]) follows the name as `(now: ...)`, in the room the
 * row has left once the marks, the name and the tag are in, and not at all when that is too little
 * to say anything. It is the peer's own text: it never shortens the name the chat is fixed to.
 */
internal fun peerRow(peer: PeerEntry, width: Int, consoleSafe: Boolean): String {
    val right = "${peer.transport.label} "
    val fixed = " ${if (peer.unread) "!" else " "}${if (peer.favorite) "*" else " "} ${displayText(peer.name, consoleSafe)}"
    val spare = width - right.cellWidth() - 1 - fixed.cellWidth()
    // With that much room to spare the row is cut, if at all, inside the claim: it is cut from its end.
    val claim = peer.claims
        ?.takeIf { spare >= MIN_CLAIM_CELLS }
        ?.let { " (now: ${displayText(it, consoleSafe)})" }
        .orEmpty()
    return twoColumnRow(fixed + claim, right, width)
}

/** The least room worth giving to `(now: ...)`: the words, one character of the name and an ellipsis. */
private const val MIN_CLAIM_CELLS = 12

/**
 * A private chat: [ChatScreen] titled "DM with [peerName]". `Esc` (handled by the shell) goes back
 * to the peers list. [peerName] comes from the peer and is sanitized when drawn. [sendHold] holds
 * `Enter` while the DM cannot take a line yet (see [ChatScreen] and [DmSession.sendHold]).
 */
@Composable
fun DmScreen(
    peerName: String,
    messages: List<BitchatMessage>,
    nickname: String,
    size: IntSize,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    editor: LineEditor = remember { LineEditor() },
    myPeerId: String? = null,
    mediaSizes: Map<String, Long> = emptyMap(),
    errorMessage: String? = null,
    formatTime: (Instant) -> String = ::utcClockTime,
    sendHold: () -> String? = NoHold,
) {
    val theme = LocalTuiTheme.current
    ChatScreen(
        messages = messages,
        nickname = nickname,
        size = size,
        onSend = onSend,
        modifier = modifier,
        title = "DM with $peerName",
        titleColor = theme.lora,
        editor = editor,
        myPeerId = myPeerId,
        mediaSizes = mediaSizes,
        errorMessage = errorMessage,
        formatTime = formatTime,
        sendHold = sendHold,
    )
}
